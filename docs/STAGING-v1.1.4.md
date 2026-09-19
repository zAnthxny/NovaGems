# NovaCoins v1.1.4 — checklist de staging

Instalar y probar cada distribución por separado. Nunca colocar ambos JAR en `plugins/` al mismo tiempo.

## Base

- [ ] Crear backup verificable de configuración, base de datos y recovery journal.
- [ ] Usar Paper 1.21.x sobre Java 21.
- [ ] Probar con PlaceholderAPI instalado y, por separado, sin PlaceholderAPI.

## Arranque y dependencias

- [ ] Arranque limpio con `NovaCoins-1.1.4.jar` y acceso del host a Maven Central.
- [ ] Confirmar en logs que Paper resolvió HikariCP 7.0.2, sqlite-jdbc 3.51.1.0 y mysql-connector-j 9.6.0.
- [ ] Arranque limpio con `NovaCoins-1.1.4-offline.jar` y acceso externo bloqueado.
- [ ] Confirmar ausencia de conflictos de clase y que sólo existe una instancia de NovaCoins.
- [ ] Confirmar Java 21, Paper 1.21.x y `api-version: 1.21`.

## Storage

- [ ] Crear una base SQLite nueva; validar saldo, historial, reward y reinicio.
- [ ] Migrar una copia de una base v1.1.3; validar balances, transacciones, estados de delivery y auditoría.
- [ ] Conectar a MySQL/MariaDB; validar Hikari, esquema v5, saldo e historial.
- [ ] Cortar DB antes de una operación y restaurarla.
- [ ] Simular pérdida de conexión inmediatamente después de COMMIT; confirmar `operation_id` exactly-once.

## Sesión y recovery

- [ ] Desconectar a 29:59 y confirmar 0 monedas.
- [ ] Completar 30:00.001 online y recibir +10 con un aviso.
- [ ] Completar 60 minutos y recibir +20 exactamente una vez.
- [ ] Entrar con una cuenta veterana de 4,000 horas históricas y confirmar 0 retroactivo.
- [ ] Completar rewards con DB caída, restaurar DB online y recibir un aviso recuperado.
- [ ] Recuperar tres rewards estando offline; reconectar y recibir un único resumen, sin repetirlo al siguiente login.
- [ ] Llenar el buffer de completed rewards; desconectar/reconectar y verificar que no se pierde ni duplica.
- [ ] Reiniciar con journals pendientes y confirmar orden por `accountSequence`.

## Apagados y fallos

- [ ] Ejecutar `/stop` durante escrituras de journal y mutaciones DB lentas; revisar que el writer cierre al final.
- [ ] Ejecutar kill inesperado después de COMMIT y antes de remover journal; verificar replay idempotente.
- [ ] Reiniciar y reabrir después de `/stop`, restart y forced kill; comparar saldo e historial.
- [ ] Confirmar que cualquier timeout de shutdown registra los tres contadores pendientes y no declara cierre limpio.

## Entregas externas

- [ ] Probar una reward `COMMAND` real cuyo receptor acepte `<operation_id>`.
- [ ] Reiniciar tras `DELIVERY_STARTED`; confirmar `MANUAL_REVIEW` sin reentrega automática.
- [ ] Confirmar que journals `DELIVERED` y `REFUNDED` obsoletos se limpian sin repetir efectos.

## Tienda

- [ ] Compra con saldo suficiente y rechazo con saldo insuficiente.
- [ ] Double click y spam sobre la misma compra; confirmar una sola operación.
- [ ] Entrega `ITEM` con inventario disponible y lleno.
- [ ] Entrega `COMMAND` con receptor idempotente.
- [ ] Navegación por páginas y categorías.
- [ ] Reload válido e inválido con un GUI anterior todavía abierto.

## Matriz SQLite/MySQL

- [ ] SQLite normal y simulación de base bloqueada/fallo cuando el entorno lo permita.
- [ ] MySQL: desconectar y reconectar la DB.
- [ ] MySQL: completar reward durante outage y recuperar exactamente una vez.
- [ ] MySQL: intentar purchase durante outage y comprobar orden/bloqueo seguro.
- [ ] MySQL: `admin give` con conexión ambigua después de COMMIT y reconciliar sin duplicar.
- [ ] MySQL: ejecutar recovery con backlog mixto y validar `accountSequence`.

## Operación general

- [ ] `/watacoins`, `/watacoins balance`, `/watacoins help`, PlaceholderAPI, GUI y categorías.
- [ ] Confirmar que usuarios normales no ven `admin` en tab completion y que sólo OP puede ejecutarlo.
- [ ] `/watacoins admin status`, `review` y `recovery`; confirmar la doble ejecución requerida para resolver una revisión.
- [ ] Configurar una webhook de staging nueva y provocar una alerta controlada sin bloquear el hilo principal.
- [ ] ActivityGuard con movimiento, construcción, inventario y chat normales.
- [ ] Escenarios de carga de 100, 250 y 500 jugadores.
- [ ] Observar TPS, MSPT, GC, latencia DB, journal queue y recovery pending durante carga.
- [ ] Confirmar en `/watacoins admin status` y consola la salud de storage/journal, recovery pendiente, manual review, cola webhook y cualquier cierre incompleto.
- [ ] Revisar logs: sin credenciales, sin spam de retries y sin warnings de drivers/classloader.
