// Run npm ci --ignore-scripts, then npm run build. Runtime has no npm dependencies.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {transformSync} from '@babel/core';
import presetEnv from '@babel/preset-env';
import {createHash} from 'node:crypto';
const root=path.dirname(fileURLToPath(import.meta.url));
const expected={Readability:'34dcab3d0832d0019f02990eed6b6124e029e8c32b9f0c6f2550544ff8dff174',JSDOMParser:'6b8eae46939f187ef4565c67f452a9098211daea80e50c9aea74d72015b73680'};
for (const name of ['Readability', 'JSDOMParser']) {
  const source=fs.readFileSync(path.join(root,'upstream',name+'.js'),'utf8');
  if(createHash('sha256').update(source).digest('hex') !== expected[name]) throw new Error('Unexpected upstream source: '+name);
  const result=transformSync(source, {
    filename:name+'.js',
    presets:[[presetEnv,{targets:{ie:'11'},modules:false,useBuiltIns:false}]],
    comments:true,compact:false
  });
  fs.writeFileSync(path.join(root,'../../app/src/main/resources/readability',name+'.js'),result.code+'\n');
}
