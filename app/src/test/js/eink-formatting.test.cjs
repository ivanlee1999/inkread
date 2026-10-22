// Focused runtime tests: deterministic DOM geometry, not a substitute for WebView tests.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const runtime = fs.readFileSync(path.join(__dirname, '../../main/assets/eink-formatting.js'), 'utf8');
const defaults = {fontSize:18,lineHeight:1,marginLeft:24,marginRight:24,marginTop:24,marginBottom:24,
    letterSpacing:.5,wordSpacing:0,textDarkness:1,textWeight:400,paragraphGap:1,paragraphIndent:0,
    alignment:'start',imageContrast:1,imageGrayscale:false,fontCss:''};

function fixture() {
    const listeners = new Map(), frameListeners = new Map(), fontListeners = new Map();
    const variables = {}, queue = new Map(), calls = [], timers = new Map();
    let serial=0, prefix=0, glyphScale=1, fontScale=1;
    const context = {console, Math, Object, Array, isFinite, innerWidth:411.42, innerHeight:760,
        _inkreadInitialFormatting:{...defaults}, _inkreadGeneration:'unchanged-document',
        NodeFilter:{SHOW_TEXT:4,FILTER_ACCEPT:1,FILTER_REJECT:2},
        requestAnimationFrame:cb=>{queue.set(++serial,cb);return serial;},
        cancelAnimationFrame:id=>queue.delete(id),
        setTimeout:cb=>{timers.set(++serial,cb);return serial;}, clearTimeout:id=>timers.delete(id),
        addEventListener:(name,cb)=>listeners.set(name,cb),removeEventListener:name=>listeners.delete(name),
        Android:{onInitialPaginationReady:(...args)=>calls.push(['ready',...args]),onRepaginated:(...args)=>calls.push(['reflow',...args])}
    };
    const style = {};
    const nodes=Array.from({length:30},(_,i)=>({data:`Paragraph${i}: `+'abcdefghijklmn 中文'.repeat(60),
        get length(){return this.data.length;}, parentElement:{tagName:'P'}}));
    function geometry(node, offset) {
        const width = parseFloat(style.width), height = parseFloat(style.height);
        const charWidth = parseFloat(variables['--reader-font-size'])*fontScale*.5*glyphScale;
        const line = parseFloat(variables['--reader-font-size'])*fontScale*Number(variables['--reader-line-height']);
        const chars = Math.max(1,Math.floor(width/charWidth)), rows = Math.max(1,Math.floor(height/line));
        let absolute = prefix + offset;
        for(let i=0;i<nodes.indexOf(node);i++) absolute+=nodes[i].length;
        const col=Math.floor(absolute/(chars*rows)), row=Math.floor(absolute/chars)%rows;
        const translation=Number((style.transform||'').match(/translateX\(-([0-9.]+)px\)/)?.[1]||0);
        const left=parseFloat(style.left)+col*context._pageStride+(absolute%chars)*charWidth-translation;
        const top=parseFloat(style.top)+row*line;
        return {left,top,right:left+charWidth,bottom:top+line,width:charWidth,height:line};
    }
    const frame={style,addEventListener:(n,cb)=>frameListeners.set(n,cb),removeEventListener:n=>frameListeners.delete(n),
        querySelectorAll:()=>[],getBoundingClientRect:()=>({width:parseFloat(style.width),height:parseFloat(style.height)}),
        get scrollWidth(){
            const last=geometry(nodes.at(-1),nodes.at(-1).length-1);
            const translation=Number((style.transform||'').match(/translateX\(-([0-9.]+)px\)/)?.[1]||0);
            const pages=Math.floor((last.left+translation-parseFloat(style.left))/context._pageStride)+1;
            return Math.round(pages*context._pageStride-parseFloat(style.columnGap));
        }};
    const fontFaceStyle={textContent:''};
    const document={readyState:'complete', documentElement:{style:{setProperty:(name,value)=>variables[name]=String(value)},
        getBoundingClientRect:()=>({width:context.innerWidth}),clientHeight:context.innerHeight},
        fonts:{ready:Promise.resolve(), addEventListener:(n,cb)=>fontListeners.set(n,cb),removeEventListener:n=>fontListeners.delete(n)},
        getElementById:id=>id==='eink-content'?frame:fontFaceStyle,
        createTreeWalker:()=>{let i=0;return {nextNode:()=>nodes[i++]||null};},
        createRange:()=>{let node,start,end;return {setStart:(n,o)=>{node=n;start=o;},setEnd:(_,o)=>end=o,
            getBoundingClientRect:()=>geometry(node,start),
            getClientRects:()=>{
                const result=[];let previous;
                for(let i=start;i<end;i++) {const rect=geometry(node,i);if(!previous||rect.top!==previous.top||rect.left<previous.left)result.push(rect);previous=rect;}
                if(previous)result.push(previous);return result;
            }};}
    };
    Object.assign(context,{document,window:context,getComputedStyle:()=>({
        fontSize:(parseFloat(variables['--reader-font-size'])*fontScale)+'px',
        lineHeight:(parseFloat(variables['--reader-font-size'])*fontScale*Number(variables['--reader-line-height']))+'px'
    })});
    vm.createContext(context);vm.runInContext(runtime,context);
    async function flush() {await Promise.resolve();while(queue.size){const work=[...queue.values()];queue.clear();work.forEach(cb=>cb());await Promise.resolve();}}
    function fireResize(width,height){context.innerWidth=width;context.innerHeight=height;listeners.get('resize')();for(const cb of timers.values())cb();timers.clear();}
    return {context,frame,nodes,calls,listeners,frameListeners,fontListeners,variables,flush,fireResize,
        setPrefix:n=>prefix=n,setGlyphScale:n=>glyphScale=n,setFontScale:n=>fontScale=n,
        anchor:()=>JSON.parse(JSON.stringify(context.InkReadFormatting.currentAnchor())),
        anchorRect:()=>{const a=context.InkReadFormatting.currentAnchor();return geometry(nodes[a.node],a.offset);}};
}

function assertAnchorVisible(f) {
    const r=f.anchorRect(),m=f.context.InkReadFormatting.effectiveMargins();
    assert.ok(r.left>=m.left-.6 && r.right<=f.context.innerWidth-m.right+.6,JSON.stringify({r,m}));
    assert.ok(r.top>=m.top-.6 && r.bottom<=f.context.innerHeight-m.bottom+.6,JSON.stringify({r,m}));
}

test('live font/spacing/margins keep exact DOM text offset, not page fraction',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor(),oldPage=f.context._currentPage;
    f.context.InkReadFormatting.apply({...defaults,fontSize:32,lineHeight:1.8,marginLeft:49,marginRight:12,marginTop:33,marginBottom:69});
    await f.flush();assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.notEqual(f.context._currentPage,oldPage);assert.equal(f.context.InkReadFormatting.documentId,'unchanged-document');
    assert.equal(f.context._pageStride,411.42);
    assert.equal(parseFloat(f.frame.style.columnGap),61);
    assert.equal(parseFloat(f.frame.style.height),760-33-69);
});

test('panel session blocks page turns and Cancel restores original style/anchor across viewport changes',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(8);const anchor=f.anchor();
    f.context.InkReadFormatting.beginSession();f.context.goToPage(15);assert.equal(f.context._currentPage,8);
    f.fireResize(411.42,390);await f.flush();assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    f.context.InkReadFormatting.apply({...defaults,fontSize:28,lineHeight:1.6});await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    f.context.InkReadFormatting.endSession(true,defaults);f.fireResize(411.42,760);await f.flush();
    assert.deepEqual(f.anchor(),anchor);assert.equal(f.context._currentPage,8);assertAnchorVisible(f);
    f.context.goToPage(9);assert.notDeepEqual(f.anchor(),anchor);
});

test('late ordinary images and font readiness reflow using the pre-event anchor',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();
    f.setPrefix(9000);f.frameListeners.get('load')();await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);assert.ok(f.context._currentPage>12);
    f.setGlyphScale(1.3);f.fontListeners.get('loadingdone')();await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

test('extreme insets are temporary effective values and retain one complete requested line',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();f.fireResize(160,210);await f.flush();
    const requested={...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96,marginTop:96,marginBottom:96};
    f.context.InkReadFormatting.apply(requested);await f.flush();
    assert.ok(parseFloat(f.frame.style.width)>=72);assert.ok(parseFloat(f.frame.style.height)>=128);
    assert.ok(f.context._totalPages>500);assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.equal(requested.marginTop,96);assert.equal(requested.marginLeft,96);
    f.fireResize(800,1000);await f.flush();
    assert.deepEqual(JSON.parse(JSON.stringify(f.context.InkReadFormatting.effectiveMargins())),{left:96,right:96,top:96,bottom:96});
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

test('minimum preview dimensions use Android-scaled computed font metrics',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();
    f.setFontScale(1.6);f.fireResize(300,300);await f.flush();
    const requested={...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96,marginTop:96,marginBottom:96};
    f.context.InkReadFormatting.apply(requested);await f.flush();
    assert.ok(parseFloat(f.frame.style.height)>=48*1.6*2.5+8-1e-6);
    assert.ok(parseFloat(f.frame.style.width)>=48*1.6*1.5-1e-6);
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.equal(requested.marginTop,96);assert.equal(requested.marginLeft,96);
    f.fireResize(1000,1200);await f.flush();
    assert.deepEqual(JSON.parse(JSON.stringify(f.context.InkReadFormatting.effectiveMargins())),{left:96,right:96,top:96,bottom:96});
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

test('image controls are variables only, pagehide removes listeners and queued reflow',async()=>{
    const f=fixture();await f.flush();f.context.InkReadFormatting.apply({...defaults,imageContrast:1.7,imageGrayscale:true,textWeight:700});await f.flush();
    assert.equal(f.variables['--reader-image-contrast'],'1.7');assert.equal(f.variables['--reader-image-grayscale'],'1');
    assert.equal(f.variables['--reader-text-weight'],'700');
    f.frameListeners.get('load')();const count=f.calls.length;f.listeners.get('pagehide')();await f.flush();
    assert.equal(f.calls.length,count);assert.equal(f.frameListeners.size,0);assert.equal(f.fontListeners.size,0);assert.ok(!f.listeners.has('resize'));
});
