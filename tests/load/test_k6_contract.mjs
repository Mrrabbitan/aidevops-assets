// Offline execution of setup/input guards with in-memory k6 stubs. No HTTP I/O.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = readFileSync(new URL('./assets.js', import.meta.url), 'utf8').replace(/^import .* from 'k6[^']*';\n/gm, '');
const issuer = 'http://localhost:19383/realms/assets';
let sequence = 0;
function token(subject) {
  const payload = Buffer.from(JSON.stringify({ sub: subject, iss: issuer, exp: Math.floor(Date.now()/1000) + 600 })).toString('base64url');
  return 'fake.' + payload + '.not-a-signature';
}
async function moduleFor(subjects, mismatch=false) {
  const people=subjects.map((subject)=>({ subject, accessToken: token(subject) }));
  globalThis.__ENV={ MODE:'online', USERS:'2', DURATION_SECONDS:'10' };
  globalThis.open=()=>JSON.stringify({ apiBase:'http://localhost:19382', issuer, projectId:'demo', seedAssetIds:['fixture-id'], accessTokens:people });
  globalThis.SharedArray=class { constructor(_,fn) {return fn();} };
  globalThis.Counter=globalThis.Rate=globalThis.Trend=class {add(){}};
  globalThis.check=(response,checks)=>Object.values(checks).every((fn)=>fn(response));
  globalThis.sleep=()=>{};
  globalThis.exec={ vu:{idInTest:1,iterationInScenario:0}, scenario:{iterationInTest:0} };
  globalThis.encoding={b64decode:(value)=>Buffer.from(value,'base64url').toString('utf8')};
  const called=[];
  globalThis.http={get:(url,options)=>{
    called.push(url);
    const bearer=options.headers.Authorization.slice(7);
    const sub=JSON.parse(Buffer.from(bearer.split('.')[1],'base64url').toString()).sub;
    return {status:200,json:()=>({ subject:mismatch?'other-subject':sub,service:false,projects:['demo'],projectPermissions:{demo:['asset.read','asset.write']} })};
  }};
  const mod=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source+'\n//case '+sequence++));
  return {mod,called};
}
{
  const {mod,called}=await moduleFor(['one','two','three']);
  const setup=mod.setup();
  assert.equal(setup.distinctValidatedUsers,3);
  assert.equal(called.length,3,'setup must verify every token, not only USERS');
  const summary=mod.handleSummary({metrics:{},state:{testRunDurationMs:1000}});
  const output=Object.values(summary).join('');
  assert.match(output,/NOT_MEASURED/);
  assert.equal(output.includes('accessToken'),false);
  assert.equal(output.includes('not-a-signature'),false);
}
{
  const {mod}=await moduleFor(['one','one']);
  assert.throws(()=>mod.setup(),/duplicate token subject/);
}
{
  const {mod}=await moduleFor(['one','two'],true);
  assert.throws(()=>mod.setup(),/failed server authorization/);
}
console.log('PASS: distinct subjects, every-token auth, server-subject mismatch, private summaries (offline only)');
