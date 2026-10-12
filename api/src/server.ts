import { createApp } from './app.js';
import { createClient, databaseConfig } from './config.js';
import { TelemetryStore } from './store.js';

async function main(): Promise<void> {
  const config = databaseConfig();
  const port = Number(process.env.PORT ?? '3000');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid_configuration');
  const client = createClient(config.uri);
  try {
    await client.connect();
    const store = new TelemetryStore(client, config.database);
    await store.initialize();
    const app = createApp(store);
    app.addHook('onClose', async () => { await client.close(); });
    for (const signal of ['SIGINT', 'SIGTERM'] as const) process.once(signal, () => { void app.close().catch(() => { process.exitCode = 1; }); });
    await app.listen({ port, host: process.env.HOST ?? '127.0.0.1' });
    process.stdout.write('API listening.\n');
  } catch (error) { await client.close(); throw error; }
}
main().catch(() => { process.stderr.write('API startup failed; check private configuration and database availability.\n'); process.exitCode = 1; });
