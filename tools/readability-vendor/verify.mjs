// Syntax-transform parity only; the application's JVM/device tests exercise the actual adapter.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const generated=path.resolve(root,'../../app/src/main/resources/readability');
const paragraph='A useful article paragraph has complete details, several clauses, and a clear ending. '.repeat(9);
const samples=[
  `<html><head><title>A full article title</title></head><body><article><h1>A full article title</h1><p>FIRST ${paragraph}</p><figure><img src="photo.jpg"/><figcaption>Photo caption</figcaption></figure><p>LAST ${paragraph}</p></article></body></html>`,
  `<html lang="zh"><head><title>中文文章标题</title></head><body><article><p>${'中文文章段落，完整内容应该保留。'.repeat(40)} &amp; &#x1f600;</p><p>最后的段落。</p></article></body></html>`,
  `<html><head><title>Readable article title</title><base href="/assets/"/><meta name="author" content="A Writer"/></head><body><article><p>${paragraph}<a href="../other#end">A useful link</a></p><img data-src="photo.jpg" data-srcset="small.jpg 1x, large.jpg 2x"/><p>${paragraph}</p></article></body></html>`,
];
function parse(directory,html) {
  const context=vm.createContext({URL,input:html});
  for (const file of ['JSDOMParser.js','Readability.js']) vm.runInContext(fs.readFileSync(path.join(directory,file),'utf8'),context);
  return JSON.stringify(vm.runInContext('new Readability(new JSDOMParser().parse(input,"https://example.org/news/story")).parse()',context));
}
for(const [index,html] of samples.entries()) {
  assert.equal(parse(path.join(root,'upstream'),html),parse(generated,html));
  console.log(`Fixture ${index+1}: original and generated core outputs are identical.`);
}
