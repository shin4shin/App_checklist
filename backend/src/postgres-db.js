import postgres from 'postgres';

export function postgresDatabase(connectionString, hyperdrive = false) {
  const sql = postgres(connectionString, {
    prepare: false, max: 1, ssl: hyperdrive ? false : 'require', connect_timeout: 10,
    types: { bigint: { to: 20, from: [20], serialize: String, parse: Number } },
  });
  function statement(query, params = []) {
    let index = 0;
    const text = query.replace(/\?/g, () => '$' + (++index))
      .replace(/\bas\s+([a-z]+[A-Z][a-zA-Z]*)/g, 'as "$1"');
    return {
      text, params,
      bind(...values) { return statement(query, values); },
      async all() { return { results: await sql.unsafe(text, params) }; },
      async first() { return (await sql.unsafe(text, params))[0] || null; },
      async run() { const rows = await sql.unsafe(text, params); return { success: true, meta: { changes: rows.count } }; },
    };
  }
  return {
    prepare: statement,
    async batch(statements) { return sql.begin(async tx => { const results = []; for (const s of statements) results.push(await tx.unsafe(s.text, s.params)); return results; }); },
    close() { return sql.end({ timeout: 5 }); },
  };
}


