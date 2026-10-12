import { createClient, databaseConfig } from './config.js';

async function main(): Promise<void> {
  const config = databaseConfig();
  const client = createClient(config.uri);
  try {
    await client.connect();
    // Connectivity only: no collections, indexes, fixtures or application mutations.
    await client.db(config.database).command({ ping: 1 });
    process.stdout.write('Database connection and ping succeeded.\n');
  } finally { await client.close(); }
}
main().catch(() => { process.stderr.write('Database check failed; check credentials, network access and Atlas allowlist privately.\n'); process.exitCode = 1; });
