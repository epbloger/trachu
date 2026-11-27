// Verify generated pages and the legal documents shared with the Android app.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const output = path.resolve(process.argv[2] || path.join(root, '_site'));
const pages = ['index.html', 'privacy.html', 'terms.html', 'developer.html'];
const docs = [['PRIVACY_POLICY.md', 'privacy.txt'], ['TERMS.md', 'terms.txt'], ['DEVELOPER.md', 'developer.txt']];
const normalize = text => text.replace(/\r\n/g, '\n');
for (const [doc, asset] of docs) {
  assert.equal(normalize(fs.readFileSync(path.join(root, 'docs', doc), 'utf8')),
    normalize(fs.readFileSync(path.join(root, 'app/src/main/assets/legal', asset), 'utf8')), `Outdated Android legal asset: ${asset}`);
}
for (const page of pages) {
  const html = fs.readFileSync(path.join(output, page), 'utf8');
  assert.match(html, /<html lang="ru">/);
  assert.match(html, /<meta name="viewport"/);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
  assert.equal(ids.length, new Set(ids).size, `Duplicate ID in ${page}`);
  for (const match of html.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
    const ref = match[1];
    if (/^(https?:|mailto:|tel:)/.test(ref)) continue;
    if (ref.startsWith('#')) {
      assert.ok(ids.includes(ref.slice(1)), `Missing anchor ${page}: ${ref}`);
      continue;
    }
    const file = path.resolve(output, ref === './' ? 'index.html' : ref.split('#')[0]);
    assert.ok(file.startsWith(output + path.sep), `Link escapes site: ${ref}`);
    assert.ok(fs.existsSync(file), `Broken local link in ${page}: ${ref}`);
  }
}
const index = fs.readFileSync(path.join(output, 'index.html'), 'utf8');
const promos = [...index.matchAll(/src="assets\/(promo-[^"]+\.svg)"/g)].map(m => m[1]);
assert.deepEqual(promos, ['promo-balance.svg','promo-analytics.svg','promo-goals.svg']);
assert.doesNotMatch(index, /assets\/screens\/|actions\/runs\/|CI #|\bQA\b/);
const readme=fs.readFileSync(path.join(root,'README.md'),'utf8');
for(const image of promos) assert.ok(readme.includes('site/assets/'+image), 'Missing README promo '+image);
assert.doesNotMatch(readme, /assets\/screens\/|actions\/runs\/|CI #|\bQA\b/);
console.log('Site checks passed: four pages, links, three promo illustrations and synchronized legal documents.');
