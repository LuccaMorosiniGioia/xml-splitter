# xml-splitter

Aplicación de línea de comandos (Java 21 + Spring Boot 4) que divide un XML grande con estructura
`Document > Policy > Claim > Transaction` en varios archivos XML válidos. Cada archivo contiene Policies
**enteras**, en el orden de entrada, y como máximo **X** `Transaction` (X configurable, por defecto 5.000).

- Lectura y escritura en *streaming* (StAX): la memoria depende del tamaño de una Policy, no del archivo.
  Un archivo de 320 MB se procesa en ~5 s con un heap de 128 MB (ver [Prueba de carga](#prueba-de-carga)).
- Una Policy (y por lo tanto un Claim) nunca se divide entre archivos.
- Se preserva todo el contenido: atributos (en su orden), namespaces, CDATA, comentarios y elementos
  desconocidos se copian tal cual.
- Ejecución "todo o nada": se escribe en una carpeta temporal y se publica con un *rename* atómico, junto con
  un `manifest.json` y un marcador `_SUCCESS`.

---

## Índice

1. [Requisitos](#requisitos)
2. [Compilar y ejecutar](#compilar-y-ejecutar)
3. [Configuración](#configuración)
4. [Salida y códigos de salida](#salida-y-códigos-de-salida)
5. [Cómo funciona](#cómo-funciona)
6. [Decisiones de diseño](#decisiones-de-diseño)
7. [Supuestos](#supuestos)
8. [Validación: alternativa de dos pasadas](#validación-alternativa-de-dos-pasadas)
9. [Tests](#tests)
10. [Prueba de carga](#prueba-de-carga)
11. [Mejoras futuras](#mejoras-futuras)
12. [Tiempo invertido y herramientas](#tiempo-invertido-y-herramientas)

---

## Requisitos

- **JDK 21** (probado con Eclipse Temurin 21.0.13). No hace falta instalar Maven: el proyecto incluye el
  *Maven Wrapper* (`mvnw` / `mvnw.cmd`), que descarga la versión correcta la primera vez.
- `JAVA_HOME` apuntando al JDK (o `java` en el `PATH`).

## Compilar y ejecutar

### Windows (PowerShell o cmd)

```powershell
# Compilar, correr los tests y generar el jar
.\mvnw.cmd clean package

# Ejecutar con el ejemplo incluido (6 Policies, 10 Transactions), máximo 4 Transactions por archivo
java -jar target\xml-splitter-1.0.0.jar --input=samples\policies.xml --max-transactions=4

# Ayuda
java -jar target\xml-splitter-1.0.0.jar --help
```

### Linux / macOS

```bash
./mvnw clean package
java -jar target/xml-splitter-1.0.0.jar --input=samples/policies.xml --max-transactions=4
java -jar target/xml-splitter-1.0.0.jar --help
```

También se puede ejecutar sin generar el jar:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--input=samples/policies.xml --max-transactions=4"
```

### Ejemplo de salida

```text
Output folder : .../samples/policies-parts
Encoding      : UTF-8
Limit (X)     : 4 Transaction per file
Totals        : 4 parts, 6 Policy, 10 Transaction
Elapsed       : 52 ms
  policies_part_0001.xml                        2 policies        3 transactions
  policies_part_0002.xml                        1 policies        2 transactions
  policies_part_0003.xml                        2 policies        4 transactions
  policies_part_0004.xml                        1 policies        1 transactions
```

## Configuración

Los valores por defecto están en `src/main/resources/application.yml`. Cualquier opción de línea de
comandos **pisa** el valor del archivo. También se puede usar un `application.yml` externo
(`--spring.config.additional-location=...`) o propiedades de Spring (`--splitter.max-transactions=...`).

| Opción CLI | Propiedad (`application.yml`) | Defecto | Descripción |
|---|---|---|---|
| `--input=<archivo>` (o primer argumento) | — | — | XML a dividir (obligatorio) |
| `--output=<carpeta>` | `splitter.output-dir` | `<carpeta del input>/<nombre>-parts` | Carpeta de salida |
| `--max-transactions=<X>` | `splitter.max-transactions` | `5000` | Máximo de Transactions por archivo (≥ 1) |
| `--header-mode=<modo>` | `splitter.header-mode` | `every-file` | `every-file` o `first-only` |
| `--trailer-mode=<modo>` | `splitter.trailer-mode` | `last-file` | `last-file` o `every-file` |
| `--split-element=<nombre>` | `splitter.split-element` | `Policy` | Elemento que nunca se divide |
| `--counted-element=<nombre>` | `splitter.counted-element` | `Transaction` | Elemento que cuenta contra X |
| `--schema=<archivo.xsd>` | — | — | Valida además cada archivo de salida contra un XSD |
| `--overwrite` | `splitter.overwrite` | `false` | Reemplaza la salida de una corrida anterior |

## Salida y códigos de salida

```text
policies-parts/
├── policies_part_0001.xml
├── policies_part_0002.xml
├── ...
├── manifest.json     # parámetros, totales y, por archivo: policies, transactions, bytes, overLimit
└── _SUCCESS          # marcador vacío: la corrida terminó bien
```

Cada archivo tiene: declaración XML (con el encoding original), comentarios/PI previos al root, el elemento
root con todos sus atributos y namespaces, el *header* (según `header-mode`), las Policies y, según
`trailer-mode`, el *trailer*.

| Código | Significado |
|---|---|
| 0 | Éxito |
| 1 | Error inesperado |
| 2 | Argumentos, configuración o XSD inválidos |
| 3 | Archivo de entrada inexistente o ilegible |
| 4 | Entrada mal formada o con estructura no soportada |
| 5 | Un archivo de salida no pasó la verificación (well-formedness, conteos o XSD) |
| 6 | La carpeta de salida existe y no se pasó `--overwrite` (o no fue generada por esta herramienta) |
| 7 | Error de escritura o al publicar la salida |

## Cómo funciona

```text
XmlDocumentReader ──► PolicyBlock (una Policy entera + su conteo de Transactions)
        │                      │
        │              PackingStrategy.startNewPart(...)?  ── sí ──► cerrar parte actual, abrir otra
        │                      │
        ▼                      ▼
  DocumentHead           PartWriter (declaración, prolog, root, header, Policies)
  DocumentTail ────────► PartSealer (trailer, cierre del root, epílogo) ── al final de la lectura
                               │
                 OutputVerifier (+ SchemaValidator opcional) ── re-lee cada parte
                               │
                 ManifestWriter ──► OutputPublisher (rename atómico staging ─► salida)
```

1. **Lectura** (`XmlDocumentReader`): recorre el XML con StAX. Guarda en memoria el *head* (declaración,
   prolog, root, header) y luego entrega **una Policy por vez** como lista de eventos, contando los
   `Transaction` a cualquier profundidad. Al llegar al cierre del root entrega el *tail* (trailer, epílogo).
2. **Empaquetado** (`SequentialGreedyPacking`): si la parte actual no está vacía y
   `transacciones actuales + transacciones de la Policy > X`, se cierra la parte y se abre otra.
3. **Escritura** (`PartWriter`): copia los eventos sin transformarlos. El root queda abierto; el cierre lo
   hace `PartSealer` cuando ya se conoce el trailer (necesario para `trailer-mode=every-file`).
4. **Verificación** (`OutputVerifier`): re-parsea cada parte (también en streaming) y comprueba que sea XML
   bien formado y que tenga exactamente las Policies y Transactions que se escribieron. Opcionalmente, valida
   contra un XSD (`SchemaValidator`).
5. **Publicación** (`ManifestWriter`, `OutputPublisher`): escribe `manifest.json` y `_SUCCESS` en la carpeta
   temporal y la renombra a la carpeta final.

**Estructura del código**

| Paquete | Responsabilidad |
|---|---|
| `core` | `XmlSplitService` (orquestación, independiente de la CLI), `StreamingSplitter`, modelo (`SplitRequest`, `SplitSettings`, `SplitResult`, `PartSummary`), `SplitException` |
| `core.packing` | `PackingStrategy` y `SequentialGreedyPacking` (la regla de corte, sin I/O) |
| `core.xml` | StAX: lectura, escritura, sellado, verificación y XSD |
| `core.output` | Carpeta temporal, publicación atómica y manifiesto |
| `cli` | Adaptador de línea de comandos: parseo, propiedades, códigos de salida |

El núcleo no tiene anotaciones de Spring ni conoce la CLI; un endpoint REST o SOAP podría llamar a
`XmlSplitService.split(SplitRequest)` sin cambios.

## Decisiones de diseño

| # | Decisión | Por qué | Trade-off |
|---|---|---|---|
| 1 | **X como límite "blando" en el borde de cada Policy.** Se cierra el archivo **antes** de una Policy que lo haría superar X. Solo se supera X si una Policy sola tiene más de X Transactions: va sola en su propio archivo (`overLimit: true` en el manifiesto). | Requisito: una Policy/Claim nunca se divide, aunque supere X. Cerrar el archivo antes mantiene la garantía "≤ X" siempre que es posible. | Como un archivo se cierra antes de una Policy que no entra, el espacio que queda sin usar siempre es menor que el tamaño de esa Policy. Las Policies rara vez superan ~100 Transactions, así que en el peor caso un archivo queda ~99 por debajo de X (< 2 % con X = 5.000); en la prueba de 320 MB el llenado promedio fue 99,6 %. La alternativa, partir una Policy para llenar exactamente X, está prohibida por los requisitos. |
| 2 | **Root + header en todos los archivos, trailer solo en el último** (configurable). | Cada archivo debe ser un documento válido y autocontenido con la jerarquía original; el trailer suele ser un resumen del lote completo. | El header se duplica (pocos bytes). Si el trailer contiene totales del lote, en los archivos parciales no coinciden con su contenido: por eso por defecto va solo en el último. |
| 3 | **Se preserva el orden de entrada.** | Reordenar (bin packing) ganaría como mucho ~2 % de llenado, y se pierde trazabilidad: la parte N es un tramo contiguo del original, comparable línea a línea. Además obligaría a retener o releer Policies. | Algunos archivos un poco menos llenos. El motivo está documentado en `SequentialGreedyPacking`. |
| 4 | **Se cuentan los `Transaction` a cualquier profundidad dentro de la Policy.** | El XML real es más complejo; no hay que asumir que cuelgan directamente de `Claim`. | Un `Transaction` fuera de una Policy (p. ej. en el header) no cuenta. |
| 5 | **Nombres de elementos configurables**, comparados por *local name*. | Barato y útil si cambian los nombres o hay prefijos de namespace. | Dos elementos con el mismo nombre local en distintos namespaces se tratarían igual. |
| 6 | **Solo CLI** (`ApplicationRunner`), núcleo independiente de la interfaz. | Es lo que pide el enunciado (ejecución local, sin GUI). | SOAP/REST quedan como mejora. |
| 7 | **`--overwrite` explícito.** Sin él, si la carpeta de salida existe y no está vacía, falla (código 6). Con él, solo reemplaza carpetas que tienen un `manifest.json` (es decir, generadas por esta herramienta). | Evita pisar resultados por accidente y, sobre todo, evita borrar una carpeta ajena si alguien pasa `--output=C:\Users\...`. | Para reutilizar una carpeta ajena hay que vaciarla a mano. |
| 8 | **Staging + rename atómico + `manifest.json`/`_SUCCESS`.** Las partes se escriben en una carpeta oculta hermana de la salida (mismo file system) y se publica con un único rename. Con `--overwrite`, la salida anterior se aparta, se publica la nueva y recién entonces se borra la anterior (si algo falla, se restaura). | Un error a mitad de un archivo de 300 MB no deja salida parcial. El manifiesto permite auditar y a un consumidor saber qué esperar. | Requiere espacio en disco para la salida completa antes de publicar (igual que sin staging). |
| 9 | **Se preserva el encoding de entrada**; se garantiza el mismo contenido, no los mismos bytes. | Los consumidores esperan el mismo encoding. | Diferencias cosméticas: comillas simples en la declaración XML, saltos de línea en el prolog, espacios dentro de etiquetas, entidades resueltas como texto. |
| 10 | **Siempre se verifica well-formedness** de la entrada (al parsear) y de cada salida (re-lectura con conteo). `--schema` opcional para XSD. | Comprueba la salida real en disco, no lo que el programa cree que escribió. | La re-lectura duplica la lectura de disco (en la prueba de carga el total igual queda en ~5 s para 320 MB). |
| 11 | **Streaming con memoria acotada**, probado con 320 MB y `-Xmx128m`. | El input supera 300 MB; DOM/Jackson necesitarían varias veces ese tamaño en heap. | El código es más detallado que con un modelo en memoria. |
| 12 | **Woodstox** como implementación StAX (en vez de la del JDK). | La del JDK pierde el orden de atributos y convierte CDATA en texto escapado; Woodstox los conserva y escribe `<x/>` para elementos vacíos, así la salida es casi idéntica a la entrada. Es Apache 2.0 y muy usado (Jackson XML, CXF). | Una dependencia más. |
| 13 | **Cierre diferido del root** (`PartSealer`). | El trailer solo se conoce al final del archivo; con `trailer-mode=every-file` las partes anteriores también lo necesitan. Como están en staging, se terminan de escribir al final. | Cada parte se abre dos veces (costo despreciable). |

## Supuestos

Tomados del análisis previo; los más riesgosos son configurables.

1. **X es blando**: se supera solo cuando una Policy sola tiene más de X Transactions (ver decisión 1).
2. **Contenido fuera de las Policies**: el root (con atributos y namespaces) y los elementos anteriores a la
   primera Policy (*header*) se repiten en cada archivo; los posteriores a la última (*trailer*) van en el
   último. Comentarios y *processing instructions* antes y después del root se copian en todos.
3. **Orden**: la salida respeta el orden de entrada.
4. **Transactions** a cualquier profundidad dentro de una Policy.
5. **Nombres** `Policy` y `Transaction` por defecto, configurables, comparados por *local name*.
6. **Interfaz**: CLI con argumentos y `application.yml`.
7. **Nombres de salida**: `<nombreDelInput>_part_0001.xml` en la carpeta de salida (por defecto
   `<input>-parts` junto al input). Falla si ya existe, salvo `--overwrite`.
8. **Errores**: todo o nada (staging + rename atómico).
9. **Encoding**: se conserva el de la declaración (o el detectado, p. ej. por BOM; UTF-8 si no hay ninguno).
   No se promete preservar byte a byte los espacios fuera de las Policies.
10. **Validación**: solo well-formedness por defecto; XSD opcional con `--schema`.
11. **Recursos**: sin objetivo explícito; la memoria queda acotada por la Policy más grande.

Supuestos adicionales tomados al implementar:

12. **Las Policies son hijas directas del root.** Si aparece una Policy anidada dentro de otro elemento
    (p. ej. `<Policies><Policy>`), se informa como estructura no soportada (código 4) en vez de producir una
    salida incorrecta. Ver la mejora [Policies anidadas](#policies-anidadas).
13. **Contenido intercalado entre Policies** (comentarios u otros elementos hermanos) viaja con la Policy
    siguiente, para no perderlo ni reordenarlo.
14. **Header y trailer son chicos** y se guardan en memoria. Como protección, si hay más de 1.000.000 de
    eventos XML fuera de cualquier Policy, se aborta con un mensaje claro (casi siempre significa que
    `split-element` está mal configurado).
15. **Una Policy entra en memoria** (el enunciado habla de ~100 Transactions como máximo habitual).
16. **No se procesan DTDs** ni entidades externas (protección contra XXE); un DOCTYPE se copia a cada
    archivo sin resolverlo.
17. **Documento sin Policies**: se genera un único archivo con root, header y trailer.
18. **X ≥ 1**; X = 1 produce una Policy por archivo.

> **Nota sobre memoria.** La memoria es plana respecto del tamaño del archivo, pero crece con la Policy más
> grande: cada Policy se guarda como eventos hasta conocer su cantidad de Transactions, que es lo que
> permite decidir si entra en el archivo actual. Es seguro bajo el supuesto de que las Policies son chicas
> (casi siempre 1 Transaction, rara vez más de 100). Mitigaciones posibles (ver
> [Mejoras futuras](#mejoras-futuras)): volcar el buffer de una Policy a un archivo temporal al superar un
> umbral, o el enfoque de [dos pasadas](#validación-alternativa-de-dos-pasadas) ya descrito (una primera
> pasada que cuente las Transactions de cada Policy).

## Validación: alternativa de dos pasadas

Se eligió **staging + rename atómico + manifiesto**: una sola pasada sobre la entrada, y la entrada mal
formada se detecta al parsear. Si falla en el byte 299.000.000, se descarta la carpeta temporal.

La alternativa válida es **validar primero y dividir después** (dos pasadas):

1. Primera pasada: parsear todo el archivo (y opcionalmente validar contra XSD) sin escribir nada.
2. Segunda pasada: dividir solo si la primera fue exitosa.

Ventajas: no se escribe nada si la entrada es inválida y el error se informa antes. Desventajas: lee el
archivo dos veces (el doble de I/O) y, aun así, necesita staging para estar protegida ante fallos durante la
escritura (disco lleno, corte). Por eso se prefirió staging: cubre ambos casos con una sola lectura. Si el
negocio necesitara "validar sin generar", se puede agregar como un modo `--validate-only` reutilizando el
mismo lector.

## Tests

```bash
./mvnw test          # Linux/macOS
.\mvnw.cmd test      # Windows
```

38 tests (JUnit 5 + AssertJ), corren en pocos segundos:

| Clase | Qué cubre |
|---|---|
| `XmlSplitServiceTest` | límite respetado; cierre antes de una Policy que desborda; Policies nunca divididas y copiadas idénticas (comparación DOM nodo a nodo); Policy sobredimensionada sola; totales e ids de Policy iguales a la entrada y en el mismo orden; Transactions anidadas; archivos llenos (1.000 Policies, X = 100 → 10 archivos); salidas bien formadas con root, atributos y namespaces; header/trailer en sus dos modos; X = 1; documento sin Policies; encoding ISO-8859-1; manifiesto; XSD válido e inválido; Policy anidada no soportada; X < 1 |
| `OutputSafetyTest` | sin `--overwrite` no pisa; con `--overwrite` reemplaza y elimina partes viejas; no pisa carpetas ajenas; carpeta vacía reutilizable; entrada truncada a mitad → sin salida y sin restos de staging; un `--overwrite` fallido conserva la salida anterior; input inexistente |
| `SplitCommandTest` | parseo de argumentos, prioridad CLI sobre `application.yml`, argumento posicional, `--help`, cada código de salida |
| `SequentialGreedyPackingTest` | la regla de corte, aislada |
| `XmlSplitterApplicationTests` | arranque del contexto Spring y binding de `application.yml` |

## Prueba de carga

La prueba grande es *opt-in* para que el build normal siga siendo rápido. Genera un XML de 320 MB con
contenido realista (namespaces, header, trailer, atributos, varios Claims; 80 % de Policies con 1
Transaction, 19 % con 2–10, 1 % con 50–120) y lo divide con **`-Xmx128m`**:

```bash
./mvnw test -Plarge-file                        # Linux/macOS
.\mvnw.cmd test -Plarge-file                    # Windows
# opciones: -Dlarge.sizeMb=500  -Dlarge.dir=<carpeta>  -Dlarge.keep=true
```

Los archivos grandes se crean en la carpeta temporal del sistema y se borran al final; el reporte queda en
`target/large-file-report.md`. El generador también se puede usar solo (`support/LargeXmlGenerator`).

**Resultado** (AMD Ryzen 7 9700X, 32 GB RAM, SSD, Windows 11, Temurin 21.0.13):

| Métrica | Valor |
|---|---|
| Entrada | 320 MB, 283.991 Policies, 779.797 Transactions |
| Salida | 157 archivos; todos menos el último con 4.892–5.000 Transactions (llenado promedio 99,6 %); el último con 2.687 |
| Tiempo (dividir + verificar + publicar) | ~5,2 s (≈ 60 MB/s) |
| Heap máximo | 128 MB (`-Xmx128m`) |
| Heap usado, pico (incluye basura aún no recolectada) | 81 MB |
| Generación vieja (objetos retenidos) | 5–7 MB, constante durante toda la corrida |
| CLI empaquetada (`java -Xmx128m -jar ...`), tiempo total con arranque de la JVM | 5,6 s; heap después de cada GC entre 2 y 9 MB |

La memoria se mantiene plana: el heap sube y baja con basura de corta vida y la generación vieja no crece
con el tamaño del archivo.

## Mejoras futuras

- **Endpoint SOAP con MTOM** (el sistema origen de TransUnion podría enviar el XML como adjunto binario sin
  cargarlo en memoria) o **REST** (`multipart/form-data` o subida a almacenamiento + id de trabajo). Ambos
  reutilizarían `XmlSplitService` tal cual.
- **Spring Batch con la Policy como ítem**: `StaxEventItemReader` leyendo Policies enteras y un writer con
  *rollover* propio por cantidad de Transactions (el `MultiResourceItemWriter` estándar corta por cantidad de
  ítems). Aporta reinicio, métricas y historial de ejecuciones; para un único archivo hoy sería
  sobreingeniería.
- **Checkpoint/reanudación**: guardar en el manifiesto la última Policy publicada y el offset para retomar
  un archivo enorme tras una caída sin reprocesarlo.
- **Escritura en paralelo**: un hilo lector y N escritores (la lectura es secuencial, pero serializar y
  verificar cada parte puede paralelizarse); la verificación posterior también es paralelizable por parte.
- **Policies anidadas** dentro de elementos contenedores: ver [abajo](#policies-anidadas).
- **Policies muy grandes**: volcar a un archivo temporal el buffer de una Policy que supere un umbral de
  eventos, o contar las Transactions de cada Policy en una primera pasada, para que la memoria no dependa de
  la Policy más grande.
- **Modo `--validate-only`** y validación XSD de la entrada.
- **Métricas/observabilidad** (Micrometer) y logs estructurados si corre en un servidor.
- **Estrategias de empaquetado alternativas** (límite duro, límite por tamaño en bytes) implementando
  `PackingStrategy`.

### Policies anidadas

Hoy una Policy dentro de un contenedor (p. ej. `Document > Group > Policy`) falla de forma explícita
(código 4): el enunciado solo define `Document > Policy > Claim > Transaction`, y dividir dentro de un
contenedor no definido sería adivinar. La extensión propuesta generaliza el manejo del root a una **pila de
elementos ancestros abiertos**:

- Si un archivo se cierra en medio de un `Group`, se cierran los contenedores abiertos y el archivo
  siguiente los vuelve a abrir con los mismos atributos.
- Entre dos Policies, si la pila cambia, se cierran y abren los contenedores que correspondan.
- Las Policies se ubican por nombre a cualquier profundidad, o mediante una ruta configurable
  (`Document/Group/Policy`).

Preguntas abiertas, que dependen del XML real:

1. ¿El contenido propio de un contenedor (su "header") se repite en cada archivo al que se extiende, como el
   header del root y de forma configurable, o aparece una sola vez?
2. Totales del contenedor (atributos de conteo, trailers con sumas) quedarían incorrectos en cada archivo.
   Recalcularlos exige conocer el esquema real, así que la propuesta es no recalcularlos nunca y
   documentarlo.
3. ¿Puede haber Policies a distintas profundidades en un mismo archivo?

## Tiempo invertido y herramientas

**Tiempo invertido**

| Etapa | Tiempo |
|---|---|
| Análisis inicial y documento de alineamiento (con Gemini) | ~2 h |
| Revisión de las aclaraciones y decisiones | ~1 h |
| Implementación y tests | ~30 min (código generado por un agente de IA a partir de las decisiones anteriores, con mi revisión y dirección) |
| README y revisión final | ~1 h |
| **Total** | **~2 h 30 min** |

El tiempo corto de implementación refleja el uso de IA; la mayor parte del esfuerzo estuvo en las decisiones
de diseño y en la revisión del resultado.

**Herramientas**

- Java 21 (Eclipse Temurin), Spring Boot 4.1, Maven Wrapper, Woodstox (StAX), JUnit 5, AssertJ.
- **Gemini 3.1 Pro**: *brainstorming* inicial y documento de alineamiento arquitectónico.
- **Cursor** (agente de IA, modelo Claude Opus 5.5 Medium): análisis de las aclaraciones, implementación,
  tests, prueba de carga y este README, revisados por mí.
