const fs = require('fs');
for (const line of fs.readFileSync('C:/Users/макс/Desktop/secrets/HexShard/.env', 'utf8').split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
  if (match) process.env[match[1]] = match[2].trim().replace(/^["']|["']$/g, '');
}
const query = process.argv[2];
(async () => {
  const response = await fetch('https://api.supabase.com/v1/projects/zqyipemjvzcxjpglmegy/database/query', {
    method: 'POST',
    headers: { Authorization: `Bearer ${process.env.SUPABASE_ACCESS_TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ query })
  });
  const result = await response.json();
  console.log(JSON.stringify({ status: response.status, result }, null, 2));
  if (!response.ok) process.exitCode = 1;
})().catch(error => { console.error(error.message); process.exitCode = 1; });
