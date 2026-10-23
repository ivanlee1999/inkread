#!/usr/bin/env node
// Optional real-browser regression runner for independent reader script sizes.
// Prerequisites: an existing Playwright installation and an authorized Chromium
// executable. This script does not install packages or modify user data.
// Usage: node tools/verify-script-fonts-browser.cjs [repository] [legacy revision]
// Environment: CHROMIUM_EXECUTABLE, INKREAD_LEGACY_REVISION, INKREAD_BROWSER_OUTPUT_DIR,
//              INKREAD_MISSING_HAN_FONT (an existing font known not to include Han).
// Browser execution is separate from Android/BOOX evidence. A launch failure is
// a blocker, never a passing test; do not bypass environment access restrictions.
const fs=require('node:fs'), path=require('node:path'), cp=require('node:child_process'), assert=require('node:assert/strict');
const {chromium}=require('playwright');
const repo = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const legacyRevision = process.argv[3] || process.env.INKREAD_LEGACY_REVISION || 'a4bbf314ee282f97354b98fe18dc46c6604952f4';
const outputDir = process.env.INKREAD_BROWSER_OUTPUT_DIR || '/tmp';
const executablePath = process.env.CHROMIUM_EXECUTABLE || '/usr/bin/chromium';
fs.mkdirSync(outputDir, { recursive: true });
const asset=n=>fs.readFileSync(path.join(repo,'app/src/main/assets',n),'utf8');
const css=fs.readFileSync(path.join(repo,'app/src/main/java/me/ash/reader/ui/page/home/reading/EInkReaderFormatting.kt'),'utf8').split('val stylesheet = """')[1].split('""".trimIndent()')[0];
const old=n=>cp.execFileSync('git',['show',legacyRevision + ':app/src/main/assets/' + n],{cwd:repo,encoding:'utf8'});
const settings={fontSize:18,chineseFontSize:18,lineHeight:1.6,marginLeft:18,marginRight:18,marginTop:16,marginBottom:16,letterSpacing:0,wordSpacing:0,textDarkness:1,textWeight:400,paragraphGap:.6,paragraphIndent:0,alignment:'left',imageContrast:1,imageGrayscale:false,fontCss:''};
const body='<div class="eink-metadata"><h1>Title标题</h1><p>Byline · Date</p></div><h2 id="heading">Heading标题</h2><p id="mixed">Hello中文，ABC１２３。 <a href="#other">linked链接text</a> tail尾声😀</p><p id="duplicates">'+('prefix '.repeat(12)+'重复duplicate target '.repeat(6)+' suffix '.repeat(12)).repeat(3)+'</p><pre id="code">code中文</pre><math><mi>漢</mi></math>'+Array.from({length:45},(_,i)=>'<p>Paragraph '+i+' '+('English中文，test。 '.repeat(30))+'</p>').join('');
const identity={itemId:'test',contentRevision:'v1',documentFingerprint:'same-source'};
function html(legacy=false,values=settings,saved=null){return '<!doctype html><html><head><style id="eink-font-faces"></style><style>'+css+'</style><script>window._inkreadGeneration="test-generation";window._inkreadInitialFormatting='+JSON.stringify(values)+';window._inkreadReadingIdentity='+JSON.stringify(identity)+';window._inkreadInitialReadingAnchor='+JSON.stringify(saved)+';window.events=[];window.Android=new Proxy({},{get:(_,name)=>(...args)=>events.push([name,...args])});'+(legacy?'':asset('eink-script-fonts.js'))+(legacy?old('eink-formatting.js'):asset('eink-formatting.js'))+(legacy?old('eink-annotations.js'):asset('eink-annotations.js'))+'</script></head><body><main id="eink-content">'+body+'</main></body></html>';}
async function open(browser,legacy=false,values=settings,saved=null,viewport={width:412,height:732}){const p=await browser.newPage({viewport});p.on('pageerror',e=>{console.error('PAGE_ERROR',e.stack);});
await p.setContent(html(legacy,values,saved));
await p.waitForFunction(()=>_didFinishInitialPagination);
await p.evaluate(()=>document.fonts.ready);
await p.waitForTimeout(100);return p;}
async function capture(page,element,start,end,other=null){return page.evaluate(({element,start,end,other})=>{const root=document.getElementById('eink-content'),model=window.InkReadScriptFonts&&InkReadScriptFonts.install(root);
const getRecords=el=>model?model.annotationNodes.filter(n=>n.parentElement===el):Array.from(el.childNodes).filter(n=>n.nodeType===3);let a=getRecords(document.getElementById(element))[0],b=other?getRecords(document.getElementById(other))[0]:a;
const range=document.createRange();
const ap=model?model.point(a,start,false):{node:a,offset:start},bp=model?model.point(b,end,true):{node:b,offset:end};range.setStart(ap.node,ap.offset);range.setEnd(bp.node,bp.offset);getSelection().removeAllRanges();getSelection().addRange(range);InkReadAnnotations.setSelectionMode(true,'test-generation');return JSON.parse(InkReadAnnotations.captureSelection('test-generation'));},{element,start,end,other});}
async function metrics(page){return page.evaluate(()=>{const root=document.getElementById('eink-content'),model=InkReadScriptFonts.install(root),p=document.getElementById('mixed'),n=model.annotationNodes.find(n=>n.parentElement===p),h=document.getElementById('heading');let a=model.range(n,0,1).getBoundingClientRect(),c=model.range(n,5,6).getBoundingClientRect();return {latinWidth:a.width,cjkWidth:c.width,latinSize:parseFloat(getComputedStyle(p).fontSize),cjkSize:parseFloat(getComputedStyle(p.querySelector('.eink-script-cjk')).fontSize),headingSize:parseFloat(getComputedStyle(h).fontSize),headingCjk:parseFloat(getComputedStyle(h.querySelector('.eink-script-cjk')).fontSize),cjkLine:parseFloat(getComputedStyle(p.querySelector('.eink-script-cjk')).lineHeight),codeSize:parseFloat(getComputedStyle(document.getElementById('code')).fontSize),wrappers:root.querySelectorAll('.eink-script-cjk').length,text:root.textContent,pages:_totalPages};});}
async function apply(page,values){await page.evaluate(v=>InkReadFormatting.apply(v),values);
await page.evaluate(()=>document.fonts.ready);
await page.waitForTimeout(100);}
(async()=>{const browser=await chromium.launch({executablePath,headless:true,args:['--no-sandbox']});
const report={browser:await browser.version(),checks:[]};try{
const legacy=await open(browser,true), modern=await open(browser,false);
const oldMixed=await capture(legacy,'mixed',3,12),newMixed=await capture(modern,'mixed',3,12);
assert.deepEqual(newMixed,oldMixed);
report.checks.push('legacy mixed-script annotation payload is byte-equivalent');
const oldDup=await capture(legacy,'duplicates',90,99),newDup=await capture(modern,'duplicates',90,99);
assert.deepEqual(newDup,oldDup);
const repeated=await legacy.evaluate(()=>{const n=document.getElementById('duplicates').firstChild,s=n.data.indexOf('duplicate'),r=document.createRange();r.setStart(n,s);r.setEnd(n,s+9);getSelection().removeAllRanges();getSelection().addRange(r);
const a=JSON.parse(InkReadAnnotations.captureSelection('test-generation'));a.prefix='';a.suffix='';return a;});
const result=await modern.evaluate(anchor=>{InkReadAnnotations.setAnnotations([{id:'repeat',anchor}], 'test-generation');
const status=InkReadAnnotations.locationStatus();
const jump=InkReadAnnotations.pageForAnnotation('repeat','test-generation');
const resize=InkReadAnnotations.beginResize('repeat','resize1','test-generation');
const selected=getSelection().toString(),capture=JSON.parse(InkReadAnnotations.captureResize('repeat','resize1','test-generation'));return {status,jump,resize,selected,capture};},repeated);
assert.equal(result.status[0].status,'attached');
assert.equal(result.resize,true);
assert.equal(result.selected,'duplicate');
assert.equal(result.capture.start,repeated.start);
assert.equal(result.capture.startNode,repeated.startNode);
assert.equal(typeof result.jump,'number');
report.checks.push('legacy repeated quotes with deliberately ambiguous contexts attach by original fingerprint and endpoints; jump/resize preserve quote');
const elementSelection=await modern.evaluate(()=>{const span=document.getElementById('mixed').querySelector('.eink-script-cjk');
const range=document.createRange();range.selectNodeContents(span);getSelection().removeAllRanges();getSelection().addRange(range);return {expected:span.textContent,actual:JSON.parse(InkReadAnnotations.captureSelection('test-generation'))};});
assert.equal(elementSelection.actual.rangeQuote,elementSelection.expected);
assert.equal(elementSelection.actual.startOffset,5);
report.checks.push('native generated-span element endpoints capture exact partial-original offset');
const cross=async page=>page.evaluate(()=>{const root=document.getElementById('eink-content'),model=window.InkReadScriptFonts&&InkReadScriptFonts.install(root),p=document.getElementById('mixed'),link=p.querySelector('a');
const a=model?model.annotationNodes.find(n=>n.parentElement===p):p.firstChild,b=model?model.annotationNodes.find(n=>n.parentElement===link):link.firstChild;
const ap=model?model.point(a,3):{node:a,offset:3},bp=model?model.point(b,8,true):{node:b,offset:8};let r=document.createRange();r.setStart(ap.node,ap.offset);r.setEnd(bp.node,bp.offset);getSelection().removeAllRanges();getSelection().addRange(r);return JSON.parse(InkReadAnnotations.captureSelection('test-generation'));});
assert.deepEqual(await cross(modern),await cross(legacy));
report.checks.push('selection across English/CJK/link fragments equals legacy payload');
const before=await metrics(modern);
await apply(modern,{...settings,chineseFontSize:36});
const after=await metrics(modern);
assert.equal(after.latinSize,18);
assert.equal(after.cjkSize,36);
assert.ok(Math.abs(after.cjkWidth/before.cjkWidth-2)<.05);
assert.equal(after.latinWidth,before.latinWidth);
assert.equal(after.headingCjk/after.headingSize,2);
assert.equal(after.codeSize,18);
assert.equal(after.cjkLine,57.6);
assert.equal(after.text,before.text);
assert.equal(after.wrappers,before.wrappers);
report.checks.push('default/fallback glyph CJK doubles while Latin dimensions, heading ratio, code size and exact text remain correct');
const missingFont=fs.readFileSync(process.env.INKREAD_MISSING_HAN_FONT || '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf').toString('base64');
const missingCss="@font-face{font-family:InkReadCjk;src:url(data:font/ttf;base64,"+missingFont+");unicode-range:U+4E00-9FFF;}";
await apply(modern,{...settings,chineseFontSize:36,fontCss:missingCss});
const missing=await metrics(modern);
assert.equal(missing.cjkSize,36);
assert.ok(Math.abs(missing.cjkWidth/after.cjkWidth-1)<.05);
report.checks.push('selected CJK face without Han glyphs falls back at the requested CJK size');
const saved=await legacy.evaluate(()=>{goToPage(10);return InkReadFormatting.exportReadingAnchor();});
const restore=await open(browser,false,{...settings,fontSize:12,chineseFontSize:48},saved,{width:320,height:260});
assert.equal(await restore.evaluate(()=>events.find(e=>e[0]==='onReadingAnchorRestore')[2]),'restored');
const originalAnchor=JSON.parse(saved).anchor;
const check=async()=>restore.evaluate(()=>{const m=InkReadScriptFonts.install(document.getElementById('eink-content')),a=InkReadFormatting.currentAnchor(),r=m.range(m.readingNodes[a.node],a.offset,a.offset+1).getBoundingClientRect(),insets=InkReadFormatting.effectiveMargins();return {anchor:a,visible:r.right>insets.left&&r.left<innerWidth-insets.right&&r.bottom>insets.top&&r.top<innerHeight-insets.bottom,rect:{left:r.left,right:r.right,top:r.top,bottom:r.bottom},wrappers:document.querySelectorAll('.eink-script-cjk').length};});
const restored=await check();
assert.deepEqual(restored.anchor,originalAnchor);
assert.equal(restored.visible,true);
await apply(restore,{...settings,fontSize:48,chineseFontSize:12});
const reversed=await check();
assert.deepEqual(reversed.anchor,originalAnchor);
assert.equal(reversed.visible,true);
assert.equal(reversed.wrappers,restored.wrappers);
report.checks.push('legacy reading anchor restores and stays visible with 48/12 and 12/48 sizes in 320x260 viewport');
await restore.evaluate(()=>document.fonts.dispatchEvent(new Event('loadingdone')));
await restore.waitForTimeout(100);
assert.deepEqual((await check()).anchor,originalAnchor);
report.checks.push('late-font reflow preserves original logical anchor');
await modern.screenshot({path:path.join(outputDir, 'inkread-script-fonts-browser.png')});
report.metrics={before,after,missing};
for(const k of Object.keys(report.metrics)) delete report.metrics[k].text;
report.anchorChecks={restored,reversed};
report.passed=report.checks.length;
fs.writeFileSync(path.join(outputDir, 'inkread-script-fonts-browser-results.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
}finally{await browser.close();}})().catch(e=>{console.error(e.stack);process.exitCode=1;});
