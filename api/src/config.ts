import { MongoClient } from 'mongodb';

export function databaseConfig(env: NodeJS.ProcessEnv = process.env): { uri: string; database: string } {
  const uri = env.MONGODB_URI;
  const database = env.MONGODB_DATABASE ?? 'rx8_telemetry';
  if (!uri || !/^mongodb(?:\+srv)?:\/\//.test(uri) || !/^[a-zA-Z0-9_-]{1,63}$/.test(database)) throw new Error('invalid_configuration');
  return { uri, database };
}
export function createClient(uri: string): MongoClient {
  return new MongoClient(uri, { appName: 'rx8-telemetry-api', serverSelectionTimeoutMS: 5000,
    connectTimeoutMS: 5000, timeoutMS: 8000, maxPoolSize: 20, minPoolSize: 0,
    retryWrites: true, writeConcern: { w: 'majority', journal: true, wtimeoutMS: 5000 }, readPreference: 'primary' });
}
