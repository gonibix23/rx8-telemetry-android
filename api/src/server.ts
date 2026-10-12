import { createApp } from './app.js';
import { createClient, databaseConfig } from './config.js';
import { ApiLog, installFatalErrorLogging, type ErrorClass } from './logging.js';
import { TelemetryStore } from './store.js';

const log = new ApiLog();
installFatalErrorLogging(log);
async function main(): Promise<void> {
  log.lifecycle('startup_started');
  let stage: ErrorClass = 'configuration_error';
  let client: ReturnType<typeof createClient> | undefined;
  try {
    const config = databaseConfig();
    const port = Number(process.env.PORT ?? '3000');
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid_configuration');
    client = createClient(config.uri);
    stage = 'database_unavailable';
    await client.connect();
    const store = new TelemetryStore(client, config.database);
    await store.initialize();
    const app = createApp(store, log);
    app.addHook('onClose', async () => { await client?.close(); });
    let closing = false;
    for (const signal of ['SIGINT', 'SIGTERM'] as const) process.once(signal, () => {
      if (closing) return;
      closing = true;
      log.lifecycle('shutdown_started');
      void app.close().then(() => { log.lifecycle('shutdown_complete'); }).catch(() => {
        log.lifecycle('shutdown_failed', 'shutdown_failed'); process.exitCode = 1;
      });
    });
    stage = 'listen_failed';
    await app.listen({ port, host: process.env.HOST ?? '127.0.0.1' });
    log.lifecycle('server_listening');
  } catch {
    log.lifecycle('startup_failed', stage);
    try { await client?.close(); } catch { log.lifecycle('shutdown_failed', 'shutdown_failed'); }
    process.exitCode = 1;
  }
}
void main();
