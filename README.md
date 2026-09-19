# NovaGems 1.1.4

Economía secundaria por tiempo de sesión para Paper 1.21.x y Java 21.

## Semántica de sesión

NovaGems concede por defecto 10 gemas por cada ciclo completo de 30 minutos de la conexión actual. Usa `System.nanoTime()`, conserva el excedente entre ciclos y admite ciclos ilimitados. Al salir, ser expulsado o detenerse el plugin se hace una última liquidación con el instante real: todo ciclo ya completado se envía a persistencia; el resto incompleto se descarta. Nunca se consulta ni se importa el playtime histórico de Minecraft.

El `ActivityGuard` conservador usa memoria fija, ingesta O(1) y evalúa como máximo cada 10 segundos. Sólo pausa tras una ventana prolongada con patrones artificiales extraordinariamente repetitivos. Caminar, construir, abrir inventarios o conversar aportan evidencia legítima y evitan falsos positivos. No sanciona ni ejecuta comandos.

## Garantías económicas

- Cada ciclo completado crea una identidad lógica inmutable y un solo `operation_id`; todos sus retries reutilizan esa identidad.
- SQL confirma cuenta y movimiento en una sola transacción antes de actualizar la caché o entregar una recompensa.
- SQLite usa un escritor serial; MySQL/MariaDB usa workers acotados por el pool. Las colas globales y por jugador también están acotadas.
- Las mutaciones de cada cuenta se ejecutan en orden sin crear threads ni executors por jugador.
- Un único `NovaGems-JournalWriter` con cola acotada realiza write, fsync y rename fuera del hilo de Paper. Una operación permanece `CAPTURING` hasta confirmar almacenamiento durable; sólo entonces pasa a SQL. El replay es *at least once* y el efecto económico es *exactly once* por `operation_id`.
- Cada mutación nueva lleva `account_sequence`; recovery ordena siempre por cuenta y secuencia, procesa lotes de 250 y corta al primer fallo de DB con backoff acotado.
- Las recompensas completadas pendientes viven en un registro acotado independiente de `PlayerSession`, por lo que disconnect/reconnect no destruye un ciclo ya ganado.
- La salud del storage (`HEALTHY`, `DEGRADED`, `UNAVAILABLE`, `RECOVERING`) y los estados de cuenta se exponen sin bloquear el thread principal.
- Las entregas distinguen `DELIVERY_PENDING`, `DELIVERY_STARTED`, `DELIVERED`, fallo seguro, ambigüedad, revisión manual y `REFUNDED`.

Antes de ejecutar una acción irreversible, NovaGems confirma `DELIVERY_STARTED`. Si el servidor reinicia antes de confirmar el resultado, pasa a `MANUAL_REVIEW`: no repite el comando ni devuelve saldo automáticamente. Una reward insegura se deshabilita sin afectar las demás. Se recomienda que el receptor acepte `<operation_id>`.

## Instalación y compilación

1. Compila con `./gradlew clean test build`.
2. Elige **una sola** distribución:
   - `build/libs/NovaGems-1.1.4.jar` (recomendada): JAR slim; Paper resuelve SQLite JDBC, MySQL Connector/J y HikariCP desde Maven durante el arranque.
   - `build/libs/NovaGems-1.1.4-offline.jar`: JAR autocontenido para hosts sin acceso a Maven durante el arranque.
3. Copia únicamente el JAR elegido a `plugins/`.
4. Inicia Paper 1.21.x con Java 21.

No instales ambos JAR simultáneamente. Paper y PlaceholderAPI nunca se incluyen. El artefacto offline conserva los paquetes JDBC originales para que `ServiceLoader`, los drivers y Hikari funcionen con sus nombres oficiales; el aislamiento del classloader de Paper evita necesitar relocations aquí.

El slim necesita acceso al repositorio Maven configurado por Paper en el primer arranque; si la resolución falla, Paper puede no cargar NovaGems. Los arranques posteriores reutilizan la caché de libraries. El tamaño pequeño corresponde al artefacto de NovaGems: las dependencias externalizadas siguen ocupando espacio en la caché del servidor. Para un host sin salida a Internet usa el JAR offline.

## Comandos

- `/novagems` — abre directamente el menú de canjes.
- `/novagems balance` — muestra el saldo propio.
- `/novagems help` — muestra la ayuda en forma de lista.
- `/novagems admin give|take|set <jugador> <cantidad>` — sólo operadores.
- `/novagems admin reset <jugador>` — sólo operadores.
- `/novagems admin reload` — sólo operadores.
- `/novagems admin status` — estado compacto de economía, journal y webhook.
- `/novagems admin review [operación]` — consulta y resolución confirmada de `MANUAL_REVIEW`.
- `/novagems admin recovery [corrupt]` — ejecuta un batch seguro o lista cuarentenas.

Las acciones bajo `/novagems admin` comprueban directamente el estado OP y no pueden habilitarse mediante permisos administrativos granulares.

## PlaceholderAPI

Todos los placeholders leen caché O(1) y nunca hacen SQL:

- `%novagems_balance%`, `%novagems_balance_formatted%`
- `%novagems_lifetime_earned%`, `%novagems_lifetime_spent%`
- `%novagems_session_elapsed%`, `%novagems_cycle_elapsed%`
- `%novagems_session_remaining%`, `%novagems_cycle_remaining%`
- `%novagems_session_cycles%`, `%novagems_reward_progress_percent%`
- `%novagems_account_state%`, `%novagems_storage_health%`

## Storage y migraciones

SQLite es el valor inicial y usa WAL, `synchronous=FULL`, foreign keys y `busy_timeout`. MySQL/MariaDB usa HikariCP. El esquema v5 migra aditivamente desde v1/v2/v3/v4 sin alterar balances, acumulados o transacciones; añade estado durable para notificaciones de rewards recuperadas. Las filas históricas se marcan como ya notificadas para impedir avisos retroactivos. Los journals v1.1.1 se leen con su timestamp como semilla de migración; todas las capturas nuevas usan formato 2.

Todos los parámetros de conexión, pool, colas y caché se comparan durante reload. Si cambia uno que requiere reconstruir storage/executors, se conserva el valor activo y se informa que hace falta reiniciar. Las contraseñas nunca aparecen en logs o status.

## Recarga y tienda

`/novagems admin reload` primero parsea y valida candidatos completos de `config.yml`, `messages.yml` y `shop.yml`; sólo después intercambia las tres instantáneas. Una reward individual inválida se omite con WARN; un YAML estructuralmente inválido rechaza el candidato. Las sesiones siguen vivas.

La tienda admite tamaños de 9 a 54 en múltiplos de 9 cuando sus slots configurados son válidos. Incluye cabeza del jugador, countdown real, categorías opcionales, navegación configurable, confirmación de 27 slots y conservación de página/categoría. Un GUI obsoleto se refresca antes de aceptar una compra.

## Operación

La salud del storage, recovery y journal continúa registrándose en consola. Los comandos públicos de diagnóstico y revisión fueron retirados de la interfaz. Rewards con `price: 0` se deshabilitan: el mínimo es 1. El apagado usa timeouts separados de 5 segundos para journal y DB y reporta de forma SEVERE cualquier captura no confirmada.

## Alertas de Discord

Las alertas son opcionales y nunca controlan una mutación económica. Se envían en un worker daemon con cola acotada, timeout y deduplicación; una caída de Discord no bloquea Paper, recovery ni shutdown. Configura una webhook nueva únicamente en `plugins/NovaGems/config.yml` y reinicia o ejecuta `/novagems admin reload`:

```yaml
alerts:
  discord:
    enabled: true
    webhook-url: "PEGA_AQUÍ_UNA_WEBHOOK_NUEVA"
    timeout-millis: 3000
    dedupe-seconds: 300
```

La URL es una credencial. No debe incorporarse al JAR, publicarse ni guardarse en control de versiones.
