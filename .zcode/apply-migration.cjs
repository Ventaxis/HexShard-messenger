// Usage: node .zcode/apply-migration.cjs <sql-file-path> [--rollback]
// Runs the SQL file against production via the Management API. Values never printed.
const fs = require('fs');
for (const line of fs.readFileSync('C:/Users/макс/Desktop/secrets/HexShard/.env', 'utf8').split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
  if (match) process.env[match[1]] = match[2].trim().replace(/^["']|["']$/g, '');
}
const sqlFile = process.argv[2];
const rollback = process.argv.includes('--rollback');
let sql = fs.readFileSync(sqlFile, 'utf8');
if (rollback) sql = `BEGIN;\n${sql}\nROLLBACK;`;
(async () => {
  const response = await fetch('https://api.supabase.com/v1/projects/zqyipemjvzcxjpglmegy/database/query', {
    method: 'POST',
    headers: { Authorization: `Bearer ${process.env.SUPABASE_ACCESS_TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: sql })
  });
  const result = await response.json();
  if (!response.ok) { console.log(JSON.stringify({ status: response.status, error: result })); process.exit(1); }
  console.log(JSON.stringify({ status: response.status, rows: Array.isArray(result) ? result.length : 0 }));
})();
