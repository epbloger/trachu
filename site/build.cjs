// Build the public website from the canonical legal documents, without dependencies.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const output = path.resolve(process.argv[2] || path.join(root, '_site'));
const escape = value => value.replace(/[&<>"']/g, char =>
  ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[char]);
function inline(value) {
  const parts = [];
  let last = 0;
  for (const match of value.matchAll(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g)) {
    parts.push(escape(value.slice(last, match.index)));
    parts.push('<a href="' + escape(match[2]) + '">' + escape(match[1]) + '</a>');
    last = match.index + match[0].length;
  }
  parts.push(escape(value.slice(last)));
  return parts.join('');
}
function render(source) {
  return source.trim().replace(/\r\n/g, '\n').split(/\n\s*\n/).map(block => {
    if (block.startsWith('# ')) return '<h1>' + inline(block.slice(2)) + '</h1>';
    if (block.startsWith('## ')) return '<h2>' + inline(block.slice(3)) + '</h2>';
    if (block.split('\n').every(line => line.startsWith('- '))) {
      return '<ul>' + block.split('\n').map(line => '<li>' + inline(line.slice(2)) + '</li>').join('') + '</ul>';
    }
    return '<p>' + inline(block.replace(/\n/g, ' ')) + '</p>';
  }).join('\n');
}
fs.mkdirSync(output, {recursive:true});
fs.copyFileSync(path.join(__dirname, 'index.html'), path.join(output, 'index.html'));
fs.cpSync(path.join(__dirname, 'assets'), path.join(output, 'assets'), {recursive:true});
for (const [source, dest] of [['PRIVACY_POLICY.md','privacy.html'], ['TERMS.md','terms.html'], ['DEVELOPER.md','developer.html']]) {
  const content = fs.readFileSync(path.join(root, 'docs', source), 'utf8');
  const title = content.split(/\r?\n/)[0].replace(/^# /,'');
  const page = '<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>' + escape(title) + ' — ТРАЧУ.</title><link rel="stylesheet" href="assets/site.css"><link rel="icon" href="assets/icon.svg"></head><body><header class="header"><div class="wrap nav"><a class="brand" href="./"><img src="assets/icon.svg" alt="" width="40" height="40">ТРАЧУ.</a><a href="./">На главную</a></div></header><main class="wrap legal">' + render(content) + '</main><footer class="wrap footer"><nav aria-label="Документы"><a href="privacy.html">Конфиденциальность</a><a href="terms.html">Условия</a><a href="developer.html">Разработчик</a></nav></footer></body></html>';
  fs.writeFileSync(path.join(output, dest), page);
}
fs.writeFileSync(path.join(output, '.nojekyll'), '');
