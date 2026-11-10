'use strict';
// Design prototype: fictional data, memory only, no device or network access.
const copy = value => JSON.parse(JSON.stringify(value));
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const initialPlaces = [{id:'p1',name:'巷口咖啡',city:'杭州',address:'梧桐巷 18 号',note:'老街里，靠窗的位置很安静',position:null},{id:'p2',name:'西湖',city:'杭州',address:'',note:'',position:null},{id:'p3',name:'巷口咖啡',city:'苏州',address:'平江路 86 号',note:'运河边的另一家店',position:null}];
const initialRecords = [
 {id:'r1',title:'秋天的第一杯热咖啡',created:'2026-11-03',time:{kind:'date',value:'2026-11-02'},placeId:'p1',tags:['日常','值得再去'],star:true,pin:false,person:'',object:'',blocks:[{type:'text',text:'靠窗的位置，能看见梧桐叶慢慢落下来。今天的拿铁有一点坚果香。'},{type:'photo',caption:'窗边的下午'}]},
 {id:'r2',title:'西湖边的那阵风',created:'2026-11-02',time:{kind:'date',value:'2026-10-26'},placeId:'p2',tags:['散步'],star:false,pin:false,person:'',object:'',blocks:[{type:'text',text:'沿着湖边走了一小段，想记住风吹过树叶的声音。'},{type:'audio',caption:'湖边，傍晚',duration:'00:18'}]},
 {id:'r3',title:'妈妈的红豆汤',created:'2026-11-01',time:{kind:'free',value:'小时候'},placeId:null,tags:['家常'],star:true,pin:false,person:'妈妈',object:'',blocks:[{type:'text',text:'放学回家，桌上总有一碗。后来自己煮，总觉得还差一点。'},{type:'checklist',items:[{text:'红豆提前浸泡',done:true},{text:'最后再加糖',done:false}]}]}
];
initialRecords.push(
 {id:'r4',title:'',created:'2026-11-04',time:{kind:'season',value:'2025',season:'autumn',start:'2025-09-01',end:'2025-11-30'},placeId:'p1',tags:['放松'],star:true,pin:false,person:'',object:'',blocks:[{type:'photo',caption:'秋天的窗边'},{type:'audio',caption:'店里播放的旋律',duration:'00:24'},{type:'text',text:'咖啡的味道，和窗外的落叶。'},{type:'photo',caption:'桌上的小花'}]},
 {id:'r5',title:'',created:'2026-11-04',time:{kind:'range',value:'',start:'2024-01-01',end:'2026-12-31',startInput:'2024',endInput:'2026'},placeId:'p1',tags:['放松','日常'],star:true,pin:false,person:'',object:'',blocks:[{type:'audio',caption:'记不清哪次去咖啡馆时录下的雨声',duration:'00:32'}]},
 {id:'r6',title:'',created:'2026-11-05',time:{kind:'season',value:'2025',season:'winter',start:'2025-12-01',end:'2026-02-28'},placeId:'p2',tags:['散步'],star:false,pin:false,person:'',object:'',blocks:[{type:'checklist',items:[{text:'带围巾',done:true},{text:'买一杯热茶',done:false},{text:'沿着湖边走走',done:false}]}]},
 {id:'r7',title:'',created:'2026-11-05',time:{kind:'free',value:'大学时',start:'2018-01-01',end:'2022-12-31',startInput:'2018',endInput:'2022'},placeId:null,tags:['校园'],star:false,pin:false,person:'',object:'',blocks:[{type:'text',text:'晚课结束后，和朋友一起去食堂吃夜宵。'}]},
 {id:'r8',title:'',created:'2026-11-06',time:{kind:'month',value:'2025-10'},placeId:'p1',tags:['日常'],star:false,pin:false,person:'',object:'',blocks:[{type:'photo',caption:'十月留下的一张照片'}]}
);
const sampleRelations = {r1: ['li-work', 'cup'], r2: ['li-school'], r3: ['mother', 'cup']};
initialRecords.forEach(record => { record.clueIds = sampleRelations[record.id] || []; });
initialRecords.forEach(record=>record.blocks.forEach((block,i)=>{block.id=record.id+'-item-'+i;}));
let blockSeq=0;
let records=copy(initialRecords), places=copy(initialPlaces), drafts={}, draftMeta={}, trash=[], route='capture', selected=null, activeDraft=null, placeId=null, sort='event', placeQuery='', trail=[], seq=10, editClock=0, toastTimer;
const emptyFilters={q:'',media:[],tag:[],person:[],object:[],time:null,star:false,placeId:null};
const filters=copy(emptyFilters);
const defaultSeasons={spring:[3,5],summer:[6,8],autumn:[9,11],winter:[12,2]};
const seasonNames={spring:'春天',summer:'夏天',autumn:'秋天',winter:'冬天'};
let seasonMonths=copy(defaultSeasons), timeForm=null, placeForm=null, playing=null, searchPanels={media:false,tag:false}, deletionOrigins={};
const $ = id => document.getElementById(id);
const meaningfulBlock = b => b.type==='text'?Boolean(b.text.trim()):b.type==='checklist'?b.items.some(i=>i.text.trim()):true;
const newDraft = key => key.startsWith('new-');
function hasChanges(key){
 const d=drafts[key], meta=draftMeta[key];
 if(!d||!meta)return false;
 const canRecoverContent=Boolean(meta.removed&&meaningfulBlock(meta.removed.block));
 if(newDraft(key)){
  return canRecoverContent||d.blocks.some(meaningfulBlock)||Boolean(d.title.trim()||d.clueIds.length||d.tags.length||d.pendingPlace||d.placeId!==meta.placeId||JSON.stringify(d.time)!==JSON.stringify(meta.time));
 }
 const original=records.find(r=>r.id===key)||trash.find(r=>r.id===key);
 return Boolean(original)&&(canRecoverContent||JSON.stringify(original)!==JSON.stringify(d));
}
const availableDrafts = () => Object.entries(drafts).filter(([key])=>(newDraft(key)||records.some(r=>r.id===key))&&hasChanges(key)).sort(([a],[b])=>draftMeta[b].updated-draftMeta[a].updated);
const btn = (text,action,id='',cls='') => `<button class="${cls}" data-action="${action}" data-id="${esc(id)}">${text}</button>`;
const placeFor = record => record.pendingPlace || places.find(p=>p.id===record.placeId);
function timeLabel(t){
 if(t.kind==='none')return '时间未定';
 if(t.kind==='year')return `${t.value} 年`;
 if(t.kind==='month')return `${t.value.slice(0,4)} 年 ${Number(t.value.slice(5))} 月`;
 if(t.kind==='season')return `${t.value} 年${seasonNames[t.season]}`;
 if(t.kind==='range')return `${t.startInput||t.start} — ${t.endInput||t.end}`;
 return t.value+(t.clock?' '+t.clock:'');
}
function validDate(value){
 if(!/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value))return false;
 const date=new Date(value+'T00:00:00Z');
 return Number.isFinite(date.getTime())&&date.toISOString().slice(0,10)===value;
}
function monthEnd(year,month){return new Date(Date.UTC(Number(year),Number(month),0)).toISOString().slice(0,10);}
function endpoint(value,end=false){
 if(/^[1-9]\d{3}$/.test(value))return value+(end?'-12-31':'-01-01');
 if(/^[1-9]\d{3}-(0[1-9]|1[0-2])$/.test(value))return end?monthEnd(value.slice(0,4),value.slice(5)):value+'-01';
 return validDate(value)?value:null;
}
function timeBounds(t){
 if(!t||t.kind==='none')return null;
 if(['range','free','season'].includes(t.kind))return t.start&&t.end?{start:t.start,end:t.end}:null;
 const start=endpoint(t.value),end=endpoint(t.value,true);
 return start&&end?{start,end}:null;
}
function rangeNote(t){
 const b=timeBounds(t);
 if(!b||!['free','range','season'].includes(t.kind))return '';
 return `${t.startInput||b.start} — ${t.endInput||b.end}`;
}
function timeMatch(recordTime,condition=filters.time){
 if(!condition)return 'certain';
 const bounds=timeBounds(recordTime);
 if(condition.kind==='none')return bounds?'none':'certain';
 const wanted=timeBounds(condition);
 if(!bounds||!wanted||bounds.end<wanted.start||bounds.start>wanted.end)return 'none';
 return bounds.start>=wanted.start&&bounds.end<=wanted.end?'certain':'possible';
}
const searchable = r => [r.title,...r.blocks.map(b=>b.text||b.caption||(b.items||[]).map(i=>i.text).join(' ')),r.tags.join(' '),placeFor(r)?.name,...recordClues(r).map(clueLabel),r.time.kind==='free'?r.time.value:''].join(' ').toLowerCase();
const mediaNames={photo:'有照片',audio:'有录音',text:'纯文字',checklist:'有清单'};
const matchesOther = r => matchesClues(r) && (!filters.placeId||r.placeId===filters.placeId) && searchable(r).includes(filters.q.trim().toLowerCase()) && (!filters.star||r.star) && (!filters.media.length||filters.media.some(type=>type==='text'?r.blocks.length>0&&r.blocks.every(b=>b.type==='text'):r.blocks.some(b=>b.type===type))) && (!filters.tag.length||filters.tag.some(tag=>r.tags.includes(tag)));
const matches = r => matchesOther(r)&&timeMatch(r.time)!=='none';
function header(title,tools='',subtitle='') {
 $('header').innerHTML=`<div class="topline"><h2>${title}</h2><div class="tools">${tools}</div></div>${subtitle?`<p class="subtitle">${subtitle}</p>`:''}`;
}
function compact(title,tools='') {
 $('header').innerHTML=`<div class="topline">${btn('‹ 返回','back','','back')}<h2 class="compact-title">${title}</h2><div class="tools">${tools}</div></div>`;
}
function photo(caption,small=false){return `<div class="photo ${small?'small':''}" role="img" aria-label="示意照片"><span>照片示意 · ${esc(caption||'未填写说明')}</span></div>`;}
function audioPreview(b){return `<div class="audio"><button class="audio-play" data-action="play" data-id="${b.id}" aria-label="${playing===b.id?'暂停':'试听'}录音示例" aria-pressed="${playing===b.id}">${playing===b.id?'Ⅱ':'▷'}</button><i aria-hidden="true">▂ ▅ ▃ ▆ ▂ ▄</i><span>${esc(b.duration)}</span></div>${b.caption?`<p class="hint">${esc(b.caption)}</p>`:''}`;}
function previewBlock(b){
 if(b.type==='text')return `<p class="preview-text">${esc(b.text)}</p>`;
 if(b.type==='photo')return photo(b.caption,true);
 if(b.type==='audio')return audioPreview(b);
 const items=b.items.filter(i=>i.text.trim());
 return `<div class="preview-checklist">${items.slice(0,2).map(i=>`<div class="check"><span>${i.done?'☑':'☐'}</span><span>${esc(i.text)}</span></div>`).join('')}<small>已完成 ${items.filter(i=>i.done).length} / ${items.length} 项</small></div>`;
}
function card(r,certainty=''){
 const p=placeFor(r),shown=r.blocks.slice(0,3),rest=r.blocks.slice(3);
 const remainder=Object.entries(blockLabels).map(([type,label])=>{const count=rest.filter(b=>b.type===type).length;return count?`${count} ${type==='photo'?'张':type==='audio'?'段':'项'}${label}`:'';}).filter(Boolean).join('、');
 return `<article class="record" data-record-id="${r.id}"><div class="card-body" data-open="${r.id}" role="link" tabindex="0" aria-label="打开记录 ${esc(r.title||timeLabel(r.time))}"><div class="meta"><span>${esc(timeLabel(r.time))}</span>${r.time.kind==='season'?`<span>${esc(rangeNote(r.time))}</span>`:''}${r.pin?'<span>置顶</span>':''}${r.star?'<span aria-label="已加星">★</span>':''}</div>${certainty==='possible'?`<div class="possible">可能在${esc(timeLabel(filters.time))}</div>`:''}${r.title?`<h3>${esc(r.title)}</h3>`:''}<div class="record-preview">${shown.map(b=>`<div class="preview-block" data-preview-type="${b.type}">${previewBlock(b)}</div>`).join('')}</div>${remainder?`<div class="remaining">还有 ${esc(remainder)}</div>`:''}<div class="meta card-clues">${p?`<span>⌖ ${esc(p.name)}</span>`:''}${r.tags.slice(0,2).map(t=>`<span class="tag">${esc(t)}</span>`).join('')}${r.tags.length>2?`<span>另有 ${r.tags.length-2} 个标签</span>`:''}</div></div></article>`;
}
function list(items,certainty=''){return items.length?items.map(r=>card(r,certainty)).join(''):'<div class="empty">这里暂时没有记录。</div>';}
function sorted(items,mode=sort){return [...items].sort((a,b)=>Number(b.pin)-Number(a.pin)|| (mode==='created'?b.created.localeCompare(a.created):mode==='date'?(b.time.value+(b.time.clock||'')).localeCompare(a.time.value+(a.time.clock||'')):0));}
function timeline(items){
 if(!items.length)return list([]);
 const years={},spanning=[],unknown=[];
 items.forEach(r=>{
  const b=timeBounds(r.time);
  if(!b){unknown.push(r);return;}
  const year=r.time.kind==='season'?r.time.value:b.start.slice(0,4);
  if(r.time.kind!=='season'&&year!==b.end.slice(0,4)){spanning.push(r);return;}
  const group=years[year]||(years[year]={exact:[],approximate:[]});
  group[r.time.kind==='date'?'exact':'approximate'].push(r);
 });
 return Object.keys(years).sort().reverse().map(year=>`<section class="timeline-year" data-year="${year}"><h3 class="section-label">${year} 年 <span>${years[year].exact.length+years[year].approximate.length} 条</span></h3>${years[year].exact.length?`<div class="time-group"><div class="hint">明确日期</div>${list(sorted(years[year].exact,'date'))}</div>`:''}${years[year].approximate.length?`<div class="time-group approximate"><div class="hint">大概时间 · 无法确定先后的记录并列</div>${list(sorted(years[year].approximate,'approximate'))}</div>`:''}</section>`).join('')+(spanning.length?`<section class="time-group"><h3 class="section-label">跨年经历 <span>${spanning.length} 条</span></h3>${list(sorted(spanning,'approximate'))}</section>`:'')+(unknown.length?`<section class="time-group"><h3 class="section-label">时间未定 <span>${unknown.length} 条</span></h3>${list(sorted(unknown,'approximate'))}</section>`:'');
}
function frame(){
 if(route==='search')document.querySelectorAll('[data-search-panel]').forEach(el=>{searchPanels[el.dataset.searchPanel]=el.open;});
 return {route,selected,activeDraft,placeId,sort,placeQuery,filters:copy(filters),searchPanels:copy(searchPanels),scroll:$('screen').scrollTop};
}
function enter(next){trail.push(frame());route=next;$('screen').scrollTop=0;}
function forgetDraft(key){delete drafts[key];delete draftMeta[key];}
function leaveEditor(){
 if(route!=='edit'||!activeDraft)return;
 rememberEditor();
 if(!hasChanges(activeDraft)) forgetDraft(activeDraft);
 activeDraft=null;
}
function validFrame(previous){
 if(previous.route==='detail')return records.some(r=>r.id===previous.selected);
 if(previous.route==='trash-detail')return trash.some(r=>r.id===previous.selected);
 if(previous.route==='place')return places.some(p=>p.id===previous.placeId);
 if(previous.route==='edit')return Boolean(drafts[previous.activeDraft])&&(newDraft(previous.activeDraft)||records.some(r=>r.id===previous.activeDraft));
 return true;
}
function restoreFrame(previous){
 if(!validFrame(previous))return back();
 ({route,selected,placeId,sort,placeQuery}=previous);
 activeDraft=route==='edit'?previous.activeDraft:null;
 Object.assign(filters,copy(previous.filters));searchPanels=copy(previous.searchPanels);render();$('screen').scrollTop=previous.scroll;
}
function back(){
 leaveEditor();
 while(trail.length){const previous=trail.pop();if(validFrame(previous)){restoreFrame(previous);return;}}
 rootPage('home');
}
function pruneRecordFrames(id){
 const keep=previous=>!(['detail','trash-detail'].includes(previous.route)&&previous.selected===id)&&!(previous.route==='edit'&&previous.activeDraft===id);
 trail=trail.filter(keep);
 Object.values(draftMeta).forEach(meta=>{if(meta.detailTrail)meta.detailTrail=meta.detailTrail.filter(keep);});
}
function rootPage(next){
 leaveEditor();trail=[];route=next;placeQuery='';placeId=null;selected=null;searchPanels={media:false,tag:false};Object.assign(filters,copy(emptyFilters));
 // Reviewer shortcuts have the same return destination as the corresponding in-app entry.
 if(next==='search'||next==='trash')trail.push({...frame(),route:'home',scroll:0});
 render();$('screen').scrollTop=0;
}
function render(){
 document.querySelector('.phone').dataset.page=route;
 document.querySelectorAll('.guide-links [data-route]').forEach(link => {
  if (link.dataset.route === route) link.setAttribute('aria-current', 'page');
  else link.removeAttribute('aria-current');
 });
 $('bottom').innerHTML='';
 if(route==='capture') capture();
 if(route==='home') home();
 if(route==='detail') detail();
 if(route==='edit') editor();
 if(route==='search') search();
 if(route==='places') placeList();
 if(route==='place') booklet();
 if(route==='trash') recycle();
 if(route==='trash-detail') trashDetail();
 if(['capture','home','places'].includes(route)) $('bottom').innerHTML=`<nav class="navigation" aria-label="主导航">${btn('随手记','capture','',route==='capture'?'active':'')}${btn('记录','home','',route!=='capture'?'active':'')}</nav>`;
}
function capture(){
 header('随手记');
 const sources = [['camera','拍照'], ['album','选图'], ['audio','录音'], ['checklist','清单']];
 const tools = sources.map(([type, label]) => btn(
  `<svg class="tool-art" aria-hidden="true" focusable="false"><use href="#art-${type}"></use></svg><strong>${label}</strong>`,
  'quick', type, 'capture-tool'
 )).join('');
 $('screen').innerHTML = `<section class="capture-page" aria-label="开始一条记录">
  ${btn('<svg class="nib-art" aria-hidden="true" focusable="false"><use href="#art-nib"></use></svg><strong>写一句</strong><span class="write-forward" aria-hidden="true">↗</span>', 'write', '', 'capture-write')}
  <div class="capture-tools">${tools}</div></section>`;
}
function recordHeader(){
 header('记录',btn('搜索全部记录','search')+btn('更多','more'));
 const pending=availableDrafts();
 const resume=pending.length===1?btn('继续草稿 <span>›</span>','resume',pending[0][0],'draft-link'):pending.length?btn(`继续草稿 · ${pending.length} 份 <span>›</span>`,'drafts','','draft-link'):'';
 return resume+`<div class="segment" aria-label="浏览记录">${btn('按时间','home','',route==='home'?'active':'')}${btn('按地点','places','',route==='places'?'active':'')}</div>`;
}
function home(){
 $('screen').innerHTML=recordHeader()+`<div class="segment">${btn('事件时间','sort','event',sort==='event'?'active':'')}${btn('最近记录','sort','created',sort==='created'?'active':'')}</div>${sort==='created'?list(sorted(records)):timeline(records)}`;
}
function recordContent(r,readOnly=false){
 const p=placeFor(r);
 return `<div class="meta">${esc(timeLabel(r.time))}</div>${r.title?`<h2>${esc(r.title)}</h2>`:''}${rangeNote(r.time)?`<p class="hint">时间范围 ${esc(rangeNote(r.time))}</p>`:''}${r.blocks.map(b=>b.type==='text'?`<p>${esc(b.text)}</p>`:b.type==='photo'?photo(b.caption):b.type==='audio'?audioPreview(b):`<div>${b.items.map(i=>`<div class="check"><span>${i.done?'☑':'☐'}</span><span>${esc(i.text)}</span></div>`).join('')}</div>`).join('')}<div class="meta">${r.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')}${p?(readOnly?`<span>⌖ ${esc(p.name)}</span>`:btn(`⌖ ${esc(p.name)}`,'place',r.placeId)):''}</div><div class="meta">${recordClues(r).map(clue=>'<span class="tag">'+esc(clueLabel(clue))+'</span>').join('')}</div><div class="hint">记录于 ${esc(r.created)}${r.modified?' · 修改于 '+esc(r.modified):''}</div>`;
}
function detail(){
 const r=records.find(r=>r.id===selected);if(!r)return back();
 compact('记录',btn('编辑','edit',r.id));
 $('screen').innerHTML=`<article class="detail">${hasChanges(r.id)?`<div class="draft-notice">另有未完成修改，当前显示上次完成的内容。${btn('继续未完成修改','resume',r.id)}</div>`:''}${recordContent(r)}<div class="detail-actions">${btn(r.star?'★ 已加星':'☆ 加星','star',r.id)}${btn(r.pin?'取消置顶':'置顶','pin',r.id)}${btn('删除','delete',r.id,'danger')}</div></article>`;
}
function trashDetail(){
 const r=trash.find(r=>r.id===selected);if(!r)return back();
 compact('回收站中的记录');
 $('screen').innerHTML=`<article class="detail"><p class="hint">只读查看上次完成的内容，恢复后可以继续编辑。</p>${hasChanges(r.id)?'<div class="draft-notice">另有未完成修改，已随记录保留。恢复后可以单独续写。</div>':''}${recordContent(r,true)}<div class="detail-actions">${btn('恢复记录','restore',r.id,'primary')}${btn('彻底删除','erase',r.id,'danger')}</div></article>`;
}
function blockOf(type){
 const id='item-'+(++blockSeq);
 if(type==='text')return {id,type,text:''};
 if(type==='photo')return {id,type,caption:''};
 if(type==='audio')return {id,type,caption:'',duration:'00:12'};
 return {id,type,items:[{text:'',done:false}]};
}
function startEdit(id=null,type='text',focusText=false){
 const original=id?records.find(r=>r.id===id):null;
 if(id&&!original)return;
 const key=id||'new-'+(++seq);
 if(!drafts[key]){
  drafts[key]=original?copy(original):{id:'r'+seq,title:'',created:'2026-11-06',time:{kind:'date',value:'2026-11-06'},placeId:route==='place'?placeId:null,tags:[],star:false,pin:false,clueIds:[],blocks:[blockOf(type)]};
  draftMeta[key]={updated:++editClock,scroll:0,placeId:drafts[key].placeId,time:copy(drafts[key].time)};
 }
 if(original)draftMeta[key].detailTrail=[...trail,frame()];
 enter('edit');activeDraft=key;render();restoreEditor(true);
 if(focusText)$('screen').querySelector('textarea')?.focus();
}
function touchDraft(){draftMeta[activeDraft].updated=++editClock;}
function rememberEditor(){
 const meta=draftMeta[activeDraft];
 meta.scroll=$('screen').scrollTop;
 meta.clues=$('screen').querySelector('.clues')?.open;
}
function rememberField(el){
 const attr=['data-field','data-block','data-caption','data-item','data-check'].find(name=>el.hasAttribute(name));
 if(!attr)return;
 const meta=draftMeta[activeDraft];
 const section=el.closest('[data-block-id]');
 meta.selector=(section?`[data-block-id="${section.dataset.blockId}"] `:'')+`[${attr}="${el.getAttribute(attr)}"]`;
 meta.focusSelector=meta.selector;
 meta.start=el.selectionStart;meta.end=el.selectionEnd;
}
function restoreEditor(restoreFocus=false){
 const meta=draftMeta[activeDraft];
 $('screen').querySelector('.clues').open=Boolean(meta.clues);
 if(restoreFocus&&meta.focusSelector)$('screen').querySelector(meta.focusSelector)?.focus({preventScroll:true});
 if(meta.selector&&meta.start!=null)$('screen').querySelector(meta.selector)?.setSelectionRange(meta.start,meta.end);
 $('screen').scrollTop=meta.scroll;
}
function refreshEditor(){touchDraft();rememberEditor();editor();restoreEditor();}
function resumeDraft(key){
 if(!drafts[key]||(!newDraft(key)&&!records.some(r=>r.id===key)))return;
 if(!newDraft(key)){
  const origin=frame();
  draftMeta[key].detailTrail=route==='detail'&&selected===key?[...trail,origin]:[...trail,origin,{...copy(origin),route:'detail',selected:key,activeDraft:null,scroll:0}];
 }
 if($('sheet').open)closeSheet();
 enter('edit');activeDraft=key;render();restoreEditor(true);
}
function draftRow([key,d]){
 const text=d.blocks.find(b=>b.type==='text'&&b.text.trim())?.text.trim();
 const checklist=d.blocks.find(b=>b.type==='checklist'&&meaningfulBlock(b))?.items.find(i=>i.text.trim())?.text;
 const image=d.blocks.find(b=>b.type==='photo'), audio=d.blocks.find(b=>b.type==='audio');
 const preview=text||checklist||image?.caption||(audio?'录音 '+audio.duration:image?'照片':draftMeta[key].removed?'已移除一项，可撤销':'');
 return btn(`${image?'<span class="draft-thumb" role="img" aria-label="草稿照片缩略图"></span>':''}<span><strong>${esc(d.title||preview?.slice(0,36)||'未完成的草稿')}</strong><small>${esc(d.title&&preview?preview.slice(0,48)+' · ':'')}${newDraft(key)?'新草稿':'已有记录的修改'}</small></span>`,'resume',key,'draft-row');
}
const blockLabels={text:'文字',photo:'照片',audio:'声音',checklist:'清单'};
function editBlockBody(block){
 if(block.type==='text')return `<textarea aria-label="文字内容" placeholder="写下一点此刻……" data-block>${esc(block.text)}</textarea>`;
 if(block.type==='checklist'){
  const rows=block.items.map((item,j)=>`<label class="check"><input type="checkbox" aria-label="事项 ${j+1} 完成" data-check="${j}" ${item.done?'checked':''}><input type="text" aria-label="清单事项 ${j+1}" data-item="${j}" value="${esc(item.text)}" placeholder="一件小事"></label>`).join('');
  return rows+btn('＋ 添加事项','add-item',block.id);
 }
 const label=blockLabels[block.type]+'说明';
 const media=block.type==='photo'?photo(block.caption,true):audioPreview({...block,caption:''});
 return media+`<input aria-label="${label}" data-caption placeholder="${label}（选填）" value="${esc(block.caption)}">`;
}
function editBlock(block,index){
 const label=blockLabels[block.type], menuLabel=`第 ${index+1} 项（${label}）的更多操作`;
 return `<section class="block" data-block-id="${block.id}"><div class="block-toolbar"><span>${label}</span><button data-action="block-menu" data-id="${block.id}" aria-label="${menuLabel}" aria-haspopup="dialog" title="${menuLabel}">⋯</button></div>${editBlockBody(block)}</section>`;
}
function editor(){
 const d=drafts[activeDraft]; if(!d){route='home';return render();}
 compact('记录这一刻',btn('完成','finish','','primary'));
 $('screen').innerHTML=`<input class="title-input" aria-label="标题（选填）" placeholder="标题，留空也可以" data-field="title" value="${esc(d.title)}">${d.blocks.map(editBlock).join('')}${btn('＋ 添加内容','add','','add-content')}<details class="clues"><summary>时间、地点与其他线索 <span class="hint">选填</span></summary>${btn(`时间　${esc(timeLabel(d.time))}`,'time')}${btn(`地点　${esc(placeFor(d)?.name||'选择或新建')}`,'pick-place')}<label class="field" for="tags">文字标签（用逗号分开）</label><input id="tags" data-field="tags" value="${esc(d.tags.join('，'))}">${clueButtons(d)}</details>`;
 const feedback=draftMeta[activeDraft].removed?`已移除一项　${btn('撤销','undo-remove',activeDraft)}`:'草稿保留在本次预览中';
 $('bottom').innerHTML=`<div class="editor-bottom"><span class="saved">${feedback}</span>${btn('放弃修改','discard','','danger')}</div>`;
}
function search(){
 compact(filters.placeId?'地点内查找':'搜索全部记录');
 const tags=[...new Set([...records.flatMap(r=>r.tags),...filters.tag])];
 const choices=(name,items)=>items.map(([value,label])=>`<label class="filter-option"><input type="checkbox" data-multi="${name}" value="${esc(value)}" ${filters[name].includes(value)?'checked':''}>${esc(label)}</label>`).join('');
 $('screen').innerHTML=`<input id="query" class="searchbox" aria-label="搜索记录" placeholder="文字、地点，或一个人的名字" value="${esc(filters.q)}">${btn('清空搜索词','clear-query')}<div id="filter-chips" class="filter-chips" aria-label="当前筛选条件"></div><div class="filters">${btn('选择时间','filter-time','','secondary')}<label class="filter-star"><input type="checkbox" data-filter="star" ${filters.star?'checked':''}>只看星标</label></div><label class="field" for="filter-place">限定地点</label><select id="filter-place" data-filter="placeId"><option value="">全部地点</option>${places.map(p=>`<option value="${p.id}" ${filters.placeId===p.id?'selected':''}>${esc(placeIdentity(p))}</option>`).join('')}</select>${clueFilters()}<details class="filter-options" data-search-panel="media" ${searchPanels.media?'open':''}><summary>内容类型 · 多选满足任一种</summary><fieldset><legend>内容类型</legend>${choices('media',Object.entries(mediaNames))}</fieldset></details><details class="filter-options" data-search-panel="tag" ${searchPanels.tag?'open':''}><summary>文字标签 · 多选满足任一个</summary><fieldset><legend>文字标签</legend>${choices('tag',tags.map(t=>[t,t]))}</fieldset></details><p class="hint">不同类别同时满足，同一类别满足任一个。首版搜索文字和手动说明，暂不识别录音里的话或照片里的文字。</p><div id="results" aria-live="polite"></div>`;
 results();
}
function filterChips(){
 const chips=[];
 if(filters.q.trim())chips.push(btn(`搜索词：${esc(filters.q)}　×`,'clear-query','','filter-chip'));
 const hasFilters=Boolean(filters.time||filters.placeId||filters.star||filters.person.length||filters.object.length||filters.media.length||filters.tag.length);
 if(filters.time)chips.push(btn(`时间：${esc(timeLabel(filters.time))}　×`,'remove-filter','time','filter-chip'));
 if(filters.placeId){
  const p=places.find(item=>item.id===filters.placeId);
  const label=p?[p.name,placeDescription(p)||p.note].filter(Boolean).join(' · '):'未知地点';
  chips.push(`<button class="filter-chip" data-action="clear-scope" title="${esc(p?placeIdentity(p):label)}">地点：${esc(label)}　×</button>`);
 }
 if(filters.media.length)chips.push(`<span class="filter-summary">内容满足任一种 ${filters.media.map(t=>btn(`${esc(mediaNames[t])}　×`,'remove-media',t,'filter-chip')).join('<span>或</span>')}</span>`);
 if(filters.tag.length)chips.push(`<span class="filter-summary">标签满足任一个 ${filters.tag.map(t=>btn(`${esc(t)}　×`,'remove-tag',t,'filter-chip')).join('<span>或</span>')}</span>`);
 for (const kind of Object.keys(clueKinds)) {
  for (const id of filters[kind]) {
   const clue = savedClues.find(item => item.id === id);
   if (clue) chips.push(btn(clueKinds[kind] + '　' + esc(clueLabel(clue)) + '　×', 'remove-clue-filter', id, 'filter-chip'));
  }
 }
 if(filters.star)chips.push(btn('已加星　×','remove-filter','star','filter-chip'));
 $('filter-chips').innerHTML=chips.join('')+(hasFilters?btn('清除筛选','clear-filters','','secondary'):'<span class="hint">全部正式记录 · 未设置筛选</span>');
}
function results(){
 compact(filters.placeId?'地点内查找':'搜索全部记录');
 filterChips();
 const candidates=records.filter(matchesOther),certain=[],possible=[];
 candidates.forEach(r=>{const certainty=timeMatch(r.time);if(certainty==='certain')certain.push(r);if(certainty==='possible')possible.push(r);});
 const unknown=filters.time&&filters.time.kind!=='none'?candidates.filter(r=>!timeBounds(r.time)).length:0;
 const count=certain.length+possible.length;
 const timeFiltered=filters.time&&filters.time.kind!=='none';
 const timeNote=filters.time&&rangeNote(filters.time)?`<p class="hint">筛选范围 ${esc(rangeNote(filters.time))}</p>`:'';
 let body='';
 if(count){
  if(timeFiltered)body=`<section data-certainty="certain"><h3 class="section-label">明确符合 <span>${certain.length} 条</span></h3>${certain.length?list(sorted(certain,'created')):'<p class="hint">没有时间明确符合的记录。</p>'}</section><section data-certainty="possible"><h3 class="section-label">可能符合 <span>${possible.length} 条</span></h3>${possible.length?'<p class="hint">这些记录的已知时间范围与筛选范围有重合，无法确定具体发生时间。</p>'+list(sorted(possible,'created'),'possible'):'<p class="hint">没有可能符合的记录。</p>'}</section>`;
  else body=list(sorted(certain,'created'));
 }else{
  const relax=(filters.time?btn('移除时间限制','remove-filter','time','secondary'):'')+(filters.media.length?btn('移除媒体类型限制','remove-filter','media','secondary'):'')+([filters.time,filters.placeId,filters.star,filters.person.length,filters.object.length,filters.media.length,filters.tag.length].some(Boolean)?btn('清除全部筛选，保留搜索词','clear-filters','','secondary'):'');
  body=`<div class="empty">没有同时符合这些条件的记录。${filters.q?'<br>可以修改搜索词，或清空后继续查找。':''}<div class="empty-actions">${relax}${filters.q?btn('清空搜索词','clear-query','','secondary'):''}</div></div>`;
 }
 $('results').innerHTML=`<div class="section-label">${count} 条记录</div>${timeNote}${body}${unknown?`<div class="hint">另有 ${unknown} 条符合其他条件、时间未定的记录。</div>${btn('查看时间未定记录','unknown-time','','secondary wide')}`:''}`;
}
function placeList(){
 $('screen').innerHTML=recordHeader()+`<input id="place-query" aria-label="查找地点" placeholder="查找店名、城市、地址或备注" value="${esc(placeQuery)}"><div id="place-results"></div>`;
 placeResults(placeQuery);
}
function placeMatches(p,q){
 const normalize=value=>value.toLowerCase().replace(/\s+/g,'');
 return [p.name,p.city,p.address,p.note].some(value=>normalize(value||'').includes(normalize(q)));
}
function placeDescription(p){return [p.city,p.address].filter(Boolean).join(' · ');}
function placeIdentity(p){return [p.name,p.city,p.address,p.note?'备注：'+p.note:''].filter(Boolean).join(' · ');}
function placeResults(query){$('place-results').innerHTML=places.filter(p=>placeMatches(p,query)).map(p=>btn(`<span class="place-icon">⌖</span><span><strong>${esc(p.name)}</strong><small>${esc(placeDescription(p)||'城市与地址未填写')}</small>${p.note?`<small class="place-note">备注：${esc(p.note)}</small>`:''}<small>${records.filter(r=>r.placeId===p.id).length} 条记录</small></span>`,'place',p.id,'place-row')).join('')||'<div class="empty">没有找到这个地点。</div>';}
function booklet(){
 const p=places.find(p=>p.id===placeId);if(!p){route='places';return render();}
 compact('地点小册',btn('编辑地点资料','edit-place',p.id));
 $('screen').innerHTML=`<section class="place-intro"><h2>${esc(p.name)}</h2><p>${esc(placeDescription(p)||'城市与地址未填写')}</p>${p.note?`<p class="place-note">备注：${esc(p.note)}</p>`:''}<p>${p.position?'已附示例位置 30.25, 120.15':'未附位置，照样可以找回记录'}</p>${btn(`在${esc(p.name)}中查找`,'place-search',p.id,'secondary wide')}${btn('在这里记一条','new','','secondary wide')}${btn('关联已有记录','link-existing','','secondary wide')}</section>${timeline(records.filter(r=>r.placeId===p.id))}`;
}
function chooseExistingRecord(){
 const candidates=records.filter(record=>record.placeId!==placeId);
 const options=candidates.map(record=>btn(`<strong>${esc(recordSummary(record))}</strong><small>${esc(timeLabel(record.time))} · ${esc(placeFor(record)?.name||'未选地点')}</small>`,'link-record',record.id,'place-choice')).join('');
 sheet(`<h3>关联已有记录</h3><div class="sheet-options">${options||'<p>没有其他可关联的记录。</p>'}</div><div class="sheet-footer">${btn('取消','close')}</div>`);
}
function editRecordPlace(id){
 const targetPlace=placeId;
 closeSheet();
 openRecord(id);
 startEdit(id);
 const draft=drafts[activeDraft];
 delete draft.pendingPlace;
 draft.placeId=targetPlace;
 $('screen').querySelector('.clues').open=true;
 refreshEditor();
}
function recordSummary(r){
 if(r.title)return r.title;
 const b=r.blocks.find(meaningfulBlock);
 if(!b)return '记录内容';
 return b.text||b.caption||(b.type==='audio'?'录音 '+b.duration:b.type==='photo'?'照片':b.items.find(i=>i.text.trim())?.text)||'清单';
}
function recycle(){
 compact('回收站');
 $('screen').innerHTML=`<p class="hint">保留到你手动清理。可打开查看上次完成的内容，恢复后再编辑。</p>${trash.length?trash.map(r=>`<article class="trash-item" data-trash-id="${r.id}">${btn(`<strong>${esc(recordSummary(r).slice(0,80))}</strong><span class="meta">${esc(timeLabel(r.time))}</span>`,'open-trash',r.id,'trash-preview')}${hasChanges(r.id)?'<div class="draft-notice">另有未完成修改，已保留</div>':''}<div class="tools">${btn('恢复','restore',r.id)}${btn('彻底删除','erase',r.id,'danger')}</div></article>`).join(''):'<div class="empty">回收站是空的。</div>'}`;
}
function sheet(content){$('sheet-content').innerHTML=content;if(!$('sheet').open)$('sheet').showModal();}
function closeSheet(){$('sheet').close();}
function message(text,action='',id='',label='撤销'){clearTimeout(toastTimer);$('toast').innerHTML=`<span>${esc(text)}</span>${action?btn(label,action,id):''}`;$('toast').style.display='flex';toastTimer=setTimeout(()=>$('toast').style.display='none',6000);}
function placePicker(query=''){
 const d=drafts[activeDraft];if(!d)return;
 const current=placeFor(d);
 sheet(`<h3>选择一个地点</h3><p>当前选中：${esc(current?.name||'不附地点')}。选择只影响这份草稿，点击完成后更新正式记录。</p><input id="pick-query" aria-label="搜索或新建地点" placeholder="搜索名称、城市、地址或备注" value="${esc(query)}"><div id="pick-results"></div><div class="sheet-footer">${btn('取消','close')}${btn('新建地点（可同名）','create-place','','primary')}</div>`);pickResults(query);
}
function pickResults(q){
 const d=drafts[activeDraft];if(!d)return;
 const candidates=d.pendingPlace?[d.pendingPlace,...places]:places;
 const rows=candidates.filter(p=>placeMatches(p,q)).map(p=>`<button class="place-choice ${d.placeId===p.id?'selected':''}" data-action="choose-place" data-id="${p.id}" aria-pressed="${d.placeId===p.id}"><strong>${esc(p.name)}${d.placeId===p.id?' · 已选中':''}</strong><small>${esc(placeDescription(p)||'城市与地址未填写')}</small>${p.note?`<small class="place-note">备注：${esc(p.note)}</small>`:''}${d.pendingPlace?.id===p.id?'<small>新地点，待随记录完成后保存</small>':''}</button>`).join('');
 $('pick-results').innerHTML=`<div class="sheet-options">${rows||'<p class="hint">没有匹配的地点，可以直接新建。</p>'}<button class="place-choice ${!d.placeId?'selected':''}" data-action="choose-place" data-id="" aria-pressed="${!d.placeId}">不附地点${!d.placeId?' · 已选中':''}</button></div>`;
}
function openPlaceForm(mode,id='',name=''){
 const p=mode==='existing'?places.find(item=>item.id===id):{name,city:'',address:'',note:'',position:null};
 if(!p)return;
 placeForm={mode,id,position:copy(p.position||null),query:name};
 const field=(key,label,value,multiline=false)=>`<label class="field" for="place-${key}">${label}</label>${multiline?`<textarea id="place-${key}">${esc(value||'')}</textarea>`:`<input id="place-${key}" value="${esc(value||'')}">`}`;
 sheet(`<h3>${mode==='existing'?'编辑地点资料':'新建地点'}</h3><p>${mode==='existing'?'保存后，所有引用这个地点的记录与小册都会显示更新后的资料。':'同名地点会独立保存；这个新地点随当前记录点击完成后一起保存。'}</p>${field('name','地点名称',p.name)}${field('city','城市（选填）',p.city)}${field('address','地址（选填）',p.address)}${field('note','备注（选填）',p.note,true)}<p id="position-note" class="hint"></p>${btn('获取当前位置（模拟）','get-position','','secondary wide')}<div id="place-error" class="hint danger" role="alert"></div><div class="sheet-footer">${btn('取消','cancel-place-form')}${btn(mode==='existing'?'保存地点资料':'选用这个新地点','save-place','','primary')}</div>`);
 renderPositionNote();
}
function renderPositionNote(){
 $('position-note').innerHTML=placeForm.position?`已附示例位置 30.25, 120.15。${btn('移除位置','remove-position','','secondary')}`:'位置选填，不影响保存地点。';
}
function savePlace(){
 const name=$('place-name').value.trim();
 if(!name){$('place-error').textContent='请填写地点名称。';return;}
 const values={name,city:$('place-city').value.trim(),address:$('place-address').value.trim(),note:$('place-note').value.trim(),position:copy(placeForm.position)};
 if(placeForm.mode==='existing'){
  const p=places.find(item=>item.id===placeForm.id);if(!p)return;
  Object.assign(p,values);
  closeSheet();render();message('地点资料已保存');
 }else{
  const d=drafts[activeDraft];if(!d)return;
  d.pendingPlace={id:'p'+(++seq),...values};d.placeId=d.pendingPlace.id;
  closeSheet();refreshEditor();
 }
}
function timePicker(purpose='record'){
 const original=copy(purpose==='filter'?(filters.time||{kind:'year',value:'2025'}):drafts[activeDraft].time);
 timeForm={purpose,original,kind:original.kind,values:{},useCurrentSeason:false};
 timeForm.values[original.kind]={...original,start:original.startInput||original.start||'',end:original.endInput||original.end||''};
 renderTimeForm();
}
function timeValues(){return timeForm.values[timeForm.kind]||(timeForm.values[timeForm.kind]={value:timeForm.kind==='year'||timeForm.kind==='season'?'2025':'',season:'spring',start:'',end:'',clock:''});}
function rememberTimeForm(){
 const values=timeValues();
 document.querySelectorAll('[data-time-field]').forEach(el=>{values[el.dataset.timeField]=el.value.trim();});
}
function seasonBounds(year,season){
 const [startMonth,endMonth]=seasonMonths[season];
 const endYear=Number(year)+(endMonth<startMonth?1:0);
 if(!/^[1-9]\d{3}$/.test(year)||endYear>9999)return null;
 return {start:`${year}-${String(startMonth).padStart(2,'0')}-01`,end:monthEnd(endYear,endMonth)};
}
function currentSeasonBounds(){
 const values=timeValues(),old=timeForm.original;
 if(!timeForm.useCurrentSeason&&old.kind==='season'&&values.value===old.value&&values.season===old.season)return {start:old.start,end:old.end};
 return seasonBounds(values.value,values.season);
}
function timeRangePreview(){
 if(timeForm.kind!=='season')return;
 rememberTimeForm();
 const b=currentSeasonBounds();
 $('season-preview').textContent=b?`实际范围 ${b.start} — ${b.end}`:'请填写有效的四位年份，跨年后也需在 9999 年以内。';
}
function renderTimeForm(){
 const kind=timeForm.kind,v=timeValues(),isFilter=timeForm.purpose==='filter';
 const kinds=[['date','具体日期'],['month','只记得月份'],['season','只记得季节'],['year','只记得年份'],['range','一段时间'],...(!isFilter?[['free','自己的说法']]:[]),['none',isFilter?'时间未定':'暂不填写']];
 const field=(key,label,type='text',placeholder='')=>`<label class="field" for="time-${key}">${label}</label><input id="time-${key}" data-time-field="${key}" type="${type}" value="${esc(v[key]||'')}" placeholder="${esc(placeholder)}">`;
 let fields='';
 if(kind==='date')fields=field('value','事件日期','date')+(!isFilter?field('clock','时刻（选填）','time'):'');
 if(kind==='month')fields=field('value','月份','month');
 if(kind==='year')fields=field('value','年份','text','例如 2025');
 if(kind==='season')fields=field('value','季节年份','text','例如 2025')+`<label class="field" for="time-season">季节</label><select id="time-season" data-time-field="season">${Object.entries(seasonNames).map(([key,label])=>`<option value="${key}" ${v.season===key?'selected':''}>${label}</option>`).join('')}</select><p id="season-preview" class="time-range-note"></p>${timeForm.original.kind==='season'?'<p class="hint">原有季节保留当时保存的范围。需要时可以主动改用当前规则。</p>'+btn('采用当前季节设置','current-season','','secondary'):''}${btn('调整季节月份','season-settings','','secondary wide')}`;
 if(kind==='free')fields=field('value','自己的时间说法','text','例如 大学时')+'<p class="hint">可选填对应范围，保留自己的说法并参与时间查找。</p>';
 if(kind==='range'||kind==='free')fields+=`<div class="time-row"><div>${field('start',kind==='free'?'范围开始（选填）':'范围开始','text','2018 / 2018-09 / 2018-09-01')}</div><div>${field('end',kind==='free'?'范围结束（选填）':'范围结束','text','2022 / 2022-06 / 2022-06-30')}</div></div><p class="hint">起止分别填写年份、月份或具体日期，保存填写精度。</p>`;
 if(kind==='none')fields='<p class="hint">没有可确定年份的记录保留在“时间未定”，可以继续通过文字、地点等查找。</p>';
 sheet(`<h3>${isFilter?'限定事件时间':'事情发生在什么时候'}</h3><label class="field" for="time-kind">时间填写方式</label><select id="time-kind">${kinds.map(([key,label])=>`<option value="${key}" ${kind===key?'selected':''}>${label}</option>`).join('')}</select><div class="time-fields">${fields}</div>${!isFilter?'<p class="hint">记录时间由软件自动保留。这里只填写事情发生的时间。</p>':''}<div id="time-error" class="hint danger" role="alert"></div><div class="sheet-footer">${btn('取消','close')}${btn(isFilter?'应用时间筛选':'使用这个时间','set-time','','primary')}</div>`);
 timeRangePreview();
}
function saveTime(){
 rememberTimeForm();
 const kind=timeForm.kind,v=timeValues(),error=text=>{$('time-error').textContent=text;};
 let t={kind,value:kind==='none'?'':v.value||''};
 if(kind==='date'){
  if(!validDate(v.value))return error('请填写有效日期。');
  if(v.clock&&!/^([01]\d|2[0-3]):[0-5]\d$/.test(v.clock))return error('请填写有效时刻。');
  if(v.clock&&timeForm.purpose!=='filter')t.clock=v.clock;
 }
 if(kind==='month'&&!/^[1-9]\d{3}-(0[1-9]|1[0-2])$/.test(v.value))return error('请填写有效月份。');
 if(kind==='year'&&!/^[1-9]\d{3}$/.test(v.value))return error('请填写四位年份。');
 if(kind==='season'){
  const bounds=currentSeasonBounds();
  if(!bounds)return error('请填写有效的四位年份，跨年范围不可超过 9999 年。');
  t={...t,season:v.season,...bounds};
 }
 if(kind==='free'&&!v.value)return error('请填写时间描述，或选择暂不填写。');
 if(kind==='range'||(kind==='free'&&(v.start||v.end))){
  const start=endpoint(v.start),end=endpoint(v.end,true);
  if(!start||!end)return error('请完整填写范围两端，使用年份、月份或具体日期。');
  if(start>end)return error('结束时间不能早于开始时间。');
  t={...t,start,end,startInput:v.start,endInput:v.end};
 }
 const purpose=timeForm.purpose;
 closeSheet();
 if(purpose==='filter'){filters.time=t;search();}
 else {drafts[activeDraft].time=t;refreshEditor();}
}
function seasonSettings(){
 rememberTimeForm();
 sheet(`<h3>季节月份</h3><p>结束月份早于开始月份表示跨到次年。调整影响新填写的季节，已存范围保持不变。</p>${Object.entries(seasonNames).map(([key,label])=>`<div class="season-row"><strong>${label}</strong>${['开始','结束'].map((name,index)=>`<label>${name}<select data-season="${key}" data-index="${index}" aria-label="${label}${name}月份">${Array.from({length:12},(_,i)=>i+1).map(month=>`<option value="${month}" ${seasonMonths[key][index]===month?'selected':''}>${month} 月</option>`).join('')}</select></label>`).join('')}</div>`).join('')}<div class="sheet-footer">${btn('取消','return-time')}${btn('保存季节设置','save-seasons','','primary')}</div>`);
}
function playAudio(id){
 playing=playing===id?null:id;
 document.querySelectorAll('[data-action="play"]').forEach(button=>{
  const active=button.dataset.id===playing;
  button.textContent=active?'Ⅱ':'▷';button.setAttribute('aria-pressed',String(active));button.setAttribute('aria-label',(active?'暂停':'试听')+'录音示例');
 });
 message(playing?'正在演示试听状态；不会播放真实音频。':'已暂停试听示意。');
}
function stopRecordAudio(r){
 const ids=[...r.blocks,...(drafts[r.id]?.blocks||[])].map(b=>b.id);
 if(ids.includes(playing))playing=null;
}
function deleteRecord(id){
 const index=records.findIndex(r=>r.id===id);if(index<0)return;
 const preserved=hasChanges(id);
 const origin=[...trail].reverse().find(validFrame);
 const r=records.splice(index,1)[0];trash.push(r);stopRecordAudio(r);
 pruneRecordFrames(id);
 if(draftMeta[id])delete draftMeta[id].detailTrail;
 back();
 deletionOrigins[id]=origin&&validFrame(origin)?copy(origin):frame();
 message(preserved?'记录与未完成修改已移入回收站。':'记录已移入回收站。','undo-delete',id);
}
function restoreRelations(r){
 if(r.placeId&&r.pendingPlace?.id!==r.placeId&&!places.some(p=>p.id===r.placeId))r.placeId=null;
}
function restoreRecord(id,undo=false){
 const index=trash.findIndex(r=>r.id===id);if(index<0)return;
 const r=trash.splice(index,1)[0],origin=deletionOrigins[id];
 restoreRelations(r);if(drafts[id])restoreRelations(drafts[id]);records.push(r);
 delete deletionOrigins[id];pruneRecordFrames(id);
 $('toast').style.display='none';
 if(undo){
  if(origin&&validFrame(origin))restoreFrame(origin);
  else {const scroll=$('screen').scrollTop;render();$('screen').scrollTop=scroll;}
  message(hasChanges(id)?'已撤销删除，未完成修改仍需单独完成。':'已撤销删除');
 }else{
  if(route==='trash-detail')back();
  else {const scroll=$('screen').scrollTop;render();$('screen').scrollTop=scroll;}
  message('已恢复 ·','view-saved',id,'查看');
 }
}
function confirmErase(id){
 const r=trash.find(item=>item.id===id);if(!r)return;
 stopRecordAudio(r);trash=trash.filter(item=>item.id!==id);forgetDraft(id);delete deletionOrigins[id];pruneRecordFrames(id);
 if(activeDraft===id)activeDraft=null;
 closeSheet();
 if(route==='trash-detail'||route==='edit')back();
 else render();
 message('已彻底删除');
}
function finish(){
 const key=activeDraft,d=drafts[key];
 if(!d){activeDraft=null;return back();}
 if(!newDraft(key)&&!records.some(r=>r.id===key)){
  activeDraft=null;back();message('这条记录已移出正式记录，恢复后再继续编辑。');return;
 }
 const blocks=d.blocks.filter(meaningfulBlock);
 if(!blocks.length){message('先留下一点内容，再点完成。');return;}
 const r=copy({...d,blocks});
 if (r.pendingClues) {
  savedClues.push(...r.pendingClues);
  delete r.pendingClues;
 }
 if(r.pendingPlace){places.push(r.pendingPlace);r.placeId=r.pendingPlace.id;delete r.pendingPlace;}
 const index=records.findIndex(item=>item.id===r.id);
 const origin=(draftMeta[key].detailTrail||[]).filter(validFrame);
 forgetDraft(key);activeDraft=null;
 if(index===-1){
  records.unshift(r);rootPage('capture');message('已保存 ·','view-saved',r.id,'查看');return;
 }
 r.modified='2026-11-06 09:41';records[index]=r;
 if(origin.length){trail=[...origin];restoreFrame(trail.pop());}
 else {rootPage('home');openRecord(r.id);}
 message('已保存');
}
function openRecord(id){
 if(!records.some(r=>r.id===id))return;
 enter('detail');selected=id;render();
}
function beginSearch(id=null){
 enter('search');searchPanels={media:false,tag:false};Object.assign(filters,copy(emptyFilters),{placeId:id});render();
}
function addContent(after=''){
 sheet(`<h3>添加一点内容</h3><div class="sheet-options" data-after="${after}">${btn('文字','add-block','text')}${btn('拍照','quick','camera')}${btn('选图','quick','album')}${btn('录音','quick','audio')}${btn('可勾选清单','add-block','checklist')}</div><div class="sheet-footer">${btn('取消','close')}</div>`);
}
function blockMenu(id){
 const blocks=drafts[activeDraft].blocks, index=blocks.findIndex(b=>b.id===id);
 if(index<0)return;
 const move=(label,action,disabled)=>`<button data-action="${action}" data-id="${id}" ${disabled?'disabled':''}>${label}</button>`;
 sheet(`<h3>第 ${index+1} 项 · ${blockLabels[blocks[index].type]}</h3><div class="sheet-options">${btn('在此后添加','insert-after',id)}${move('上移一项','move-up',index===0)}${move('下移一项','move-down',index===blocks.length-1)}${btn('移除这一项','remove-block',id,'danger')}</div><div class="sheet-footer">${btn('取消','close')}</div>`);
}
function focusBlock(block,inserted=false){
 const meta=draftMeta[activeDraft];
 const section=block&&$('screen').querySelector(`[data-block-id="${block.id}"]`);
 let target=$('screen').querySelector('.add-content');
 if(section){
  const selector=meta.selector;
  const previous=selector?$('screen').querySelector(selector):null;
  target=section.querySelector('[data-action="block-menu"]');
  if(inserted){
   if(block.type==='audio')target=section.querySelector('[data-action="play"]');
   if(block.type==='text'||block.type==='checklist')target=section.querySelector('textarea,input[type="text"]');
  }else if(section.contains(previous))target=previous;
 }
 target.focus({preventScroll:true});
 (section||target).scrollIntoView({block:'nearest'});
 if(target.dataset.action){
  const scope=block?`[data-block-id="${block.id}"] `:'';
  meta.focusSelector=scope+`[data-action="${target.dataset.action}"]`;
 }else rememberField(target);
 rememberEditor();
}
function addBlock(type,after){
 closeSheet();
 if(route!=='edit'){startEdit(null,type);return;}
 const blocks=drafts[activeDraft].blocks;
 const index=after?blocks.findIndex(b=>b.id===after)+1:blocks.length;
 if(after&&index===0)return;
 const block=blockOf(type);
 blocks.splice(index,0,block);refreshEditor();focusBlock(block,true);
}
function moveBlock(id,step){
 const blocks=drafts[activeDraft].blocks, index=blocks.findIndex(b=>b.id===id), dest=index+step;
 if(index<0||dest<0||dest>=blocks.length)return;
 closeSheet();
 const [block]=blocks.splice(index,1);blocks.splice(dest,0,block);
 refreshEditor();focusBlock(block);
}
function removeBlock(id){
 const blocks=drafts[activeDraft].blocks, index=blocks.findIndex(b=>b.id===id);
 if(index<0)return;
 closeSheet();
 const [block]=blocks.splice(index,1);
 draftMeta[activeDraft].removed={block,index,before:blocks[index-1]?.id,after:blocks[index]?.id};
 refreshEditor();focusBlock(blocks[index]||blocks[index-1]);
}
function undoRemove(key){
 if(route!=='edit'||activeDraft!==key)return;
 const meta=draftMeta[key], removed=meta.removed;
 if(!removed)return;
 const blocks=drafts[key].blocks;
 const next=blocks.findIndex(b=>b.id===removed.after), previous=blocks.findIndex(b=>b.id===removed.before);
 let index=Math.min(removed.index,blocks.length);
 if(next>=0)index=next;
 else if(previous>=0)index=previous+1;
 blocks.splice(index,0,removed.block);delete meta.removed;
 refreshEditor();focusBlock(removed.block);
}
function quick(type,after=''){
 if(type==='checklist'){startEdit(null,type);return;}
 if(type==='audio'){
  sheet(`<h3>录音交互示意</h3><p>正在模拟录音。停止后加入一段 12 秒的示例声音。</p><div class="sheet-footer" data-after="${after}">${btn('取消','close')}${btn('停止并保留示例','add-block','audio','primary')}</div>`);return;
 }
 const label=type==='camera'?'拍照':'选图';
 sheet(`<h3>${label}示意</h3><p>本次使用示例照片，确认后加入当前记录。</p><div class="sheet-footer" data-after="${after}">${btn('取消','close')}${btn('使用示例照片','add-block','photo','primary')}</div>`);
}
const actions={
 'pick-clues':openCluePicker, 'new-clue':createDraftClue, 'apply-clues':applyClues,
 'remove-clue-filter':id=>{
  const clue = savedClues.find(item=>item.id===id);
  if (!clue) return;
  filters[clue.kind] = filters[clue.kind].filter(value=>value!==id);
  search();
 },
 capture:()=>rootPage('capture'),home:()=>rootPage('home'),places:()=>rootPage('places'),
 'link-existing':chooseExistingRecord,'link-record':editRecordPlace,
 search:()=>beginSearch(),'place-search':id=>beginSearch(id),
 new:()=>startEdit(null,'text',true),write:()=>startEdit(null,'text',true),quick,edit:id=>startEdit(id),
 open:openRecord,'view-saved':id=>{if(route==='edit')back();$('toast').style.display='none';openRecord(id);},
 place:id=>{enter('place');placeId=id;render();},
 back,sort:id=>{sort=id;render();},finish,close:closeSheet,
 more:()=>sheet(`<h3>整理与管理</h3><div class="sheet-options">${btn('回收站','go-trash')}</div><div class="sheet-footer">${btn('关闭','close')}</div>`),
 'go-trash':()=>{closeSheet();enter('trash');render();},
 drafts:()=>sheet(`<h3>继续草稿 · ${availableDrafts().length} 份</h3><p>最近编辑的排在前面</p><div class="sheet-options">${availableDrafts().map(draftRow).join('')}</div><div class="sheet-footer">${btn('关闭','close')}</div>`),
 resume:resumeDraft,
 add:()=>addContent(),'insert-after':addContent,'add-block':addBlock,
 'block-menu':blockMenu,'move-up':id=>moveBlock(id,-1),'move-down':id=>moveBlock(id,1),
 play:playAudio,
 'remove-block':removeBlock,'undo-remove':undoRemove,
 'add-item':id=>{drafts[activeDraft].blocks.find(b=>b.id===id).items.push({text:'',done:false});refreshEditor();},
 discard:()=>sheet(`<h3>放弃这份修改？</h3><p>${newDraft(activeDraft)?'这份新建草稿将被移除。':'保留上一次完成的记录，移除本次未完成修改。'}</p><div class="sheet-footer">${btn('继续编辑','close')}${btn('放弃修改','confirm-discard','','danger')}</div>`),
 'confirm-discard':()=>{forgetDraft(activeDraft);activeDraft=null;closeSheet();back();},
 time:()=>timePicker('record'),
 'filter-time':()=>timePicker('filter'),
 'set-time':saveTime,
 'season-settings':seasonSettings,
 'return-time':renderTimeForm,
 'save-seasons':()=>{document.querySelectorAll('[data-season]').forEach(el=>{seasonMonths[el.dataset.season][Number(el.dataset.index)]=Number(el.value);});renderTimeForm();},
 'current-season':()=>{rememberTimeForm();timeForm.useCurrentSeason=true;renderTimeForm();},
 'pick-place':()=>placePicker(),
 'choose-place':id=>{const d=drafts[activeDraft];if(!d)return;d.placeId=id||null;if(d.pendingPlace?.id!==id)delete d.pendingPlace;closeSheet();refreshEditor();},
 'create-place':()=>openPlaceForm('new','',$('pick-query').value.trim()),
 'edit-place':id=>openPlaceForm('existing',id),
 'save-place':savePlace,
 'cancel-place-form':()=>{if(placeForm.mode==='new')placePicker(placeForm.query);else closeSheet();},
 'get-position':()=>{$('position-note').innerHTML=`示例位置：30.25, 120.15。<br>这不是设备的真实位置，确认后才附上。${btn('确认附上示例位置','attach-position','','secondary wide')}${btn('取消本次获取','dismiss-position','','secondary wide')}`;},
 'dismiss-position':renderPositionNote,
 'attach-position':()=>{placeForm.position={latitude:30.25,longitude:120.15,source:'demo',coordinateType:'prototype'};renderPositionNote();},
 'remove-position':()=>{placeForm.position=null;renderPositionNote();},
 star:id=>{const r=records.find(r=>r.id===id);r.star=!r.star;if(drafts[id])drafts[id].star=r.star;detail();},
 pin:id=>{const r=records.find(r=>r.id===id);r.pin=!r.pin;if(drafts[id])drafts[id].pin=r.pin;detail();},
 delete:deleteRecord,
 'open-trash':id=>{if(!trash.some(r=>r.id===id))return;enter('trash-detail');selected=id;render();},
 restore:id=>restoreRecord(id),
 'undo-delete':id=>restoreRecord(id,true),
 erase:id=>{if(!trash.some(r=>r.id===id))return;sheet(`<h3>彻底删除这条记录？</h3><p>${hasChanges(id)?'上次完成的内容与未完成修改都会一起删除。':'这条记录的内容会被永久删除。'}彻底删除后无法恢复。</p><div class="sheet-footer">${btn('取消','close')}${btn('彻底删除','confirm-erase',id,'danger')}</div>`);},
 'confirm-erase':confirmErase,
 'clear-filters':()=>{const q=filters.q;Object.assign(filters,copy(emptyFilters),{q});search();},
 'clear-query':()=>{filters.q='';search();},
 'remove-filter':key=>{filters[key]=copy(emptyFilters[key]);search();},
 'remove-media':id=>{filters.media=filters.media.filter(v=>v!==id);search();},
 'remove-tag':id=>{filters.tag=filters.tag.filter(v=>v!==id);search();},
 'unknown-time':()=>{filters.time={kind:'none',value:''};search();},
 'clear-scope':()=>{filters.placeId=null;search();}
};
document.addEventListener('click',event=>{
 const target=event.target.closest('button');
 if(!target){const card=event.target.closest('[data-open]');if(card)openRecord(card.dataset.open);return;}
 if(!target.closest('#toast')&&target.dataset.action!=='play')$('toast').style.display='none';
 if(target.dataset.route){rootPage(target.dataset.route);return;}
 if(target.dataset.action)actions[target.dataset.action]?.(target.dataset.id,target.closest('[data-after]')?.dataset.after);
});
document.addEventListener('focusout',event=>{
 if(route==='edit'&&activeDraft&&$('screen').contains(event.target))rememberField(event.target);
});
document.addEventListener('input',event=>{
 const el=event.target,d=drafts[activeDraft];
 if(el.id==='clue-query')renderClueOptions();
 if(el.id==='query'){filters.q=el.value;results();}
 if(el.dataset.timeField!==undefined&&timeForm?.kind==='season')timeRangePreview();
 if(el.id==='place-query'){placeQuery=el.value;placeResults(placeQuery);}
 if(el.id==='pick-query')pickResults(el.value);
 if(route!=='edit'||!d||!$('screen').contains(el))return;
 touchDraft();rememberField(el);
 if(el.dataset.field)d[el.dataset.field]=el.dataset.field==='tags'?el.value.split(/[,，]/).map(t=>t.trim()).filter(Boolean):el.value;
 const id=el.closest('[data-block-id]')?.dataset.blockId;
 if(!id)return;
 const block=d.blocks.find(b=>b.id===id);
 if(el.dataset.block!==undefined)block.text=el.value;
 if(el.dataset.caption!==undefined)block.caption=el.value;
 if(el.dataset.item!==undefined)block.items[el.dataset.item].text=el.value;
 if(el.dataset.check!==undefined)block.items[el.dataset.check].done=el.checked;
});
document.addEventListener('toggle',event=>{
 const el=event.target;
 if(route==='search'&&el.isConnected&&el.dataset.searchPanel)searchPanels[el.dataset.searchPanel]=el.open;
},true);
document.addEventListener('keydown',event=>{
 const card=event.target.closest('[data-open]');
 if(card&&event.target===card&&['Enter',' '].includes(event.key)){event.preventDefault();openRecord(card.dataset.open);}
});
document.addEventListener('change',event=>{
 const el=event.target;
 if(el.id==='time-kind'){
  rememberTimeForm();timeForm.kind=el.value;renderTimeForm();return;
 }
 if(el.dataset.timeField!==undefined){timeRangePreview();return;}
 if(el.dataset.clueId){
  if(el.checked)clueSelection.ids.add(el.dataset.clueId);
  else clueSelection.ids.delete(el.dataset.clueId);
  return;
 }
 if(el.dataset.multi){
  const field=el.dataset.multi;
  filters[field]=el.checked?[...new Set([...filters[field],el.value])]:filters[field].filter(v=>v!==el.value);
  results();return;
 }
 if(el.dataset.filter){filters[el.dataset.filter]=el.type==='checkbox'?el.checked:el.value||null;results();}
});
$('reset').addEventListener('click',()=>{
 savedClues=sampleClues.slice();clueSelection=null;
 records=copy(initialRecords);places=copy(initialPlaces);drafts={};draftMeta={};trash=[];
 activeDraft=null;sort='event';seq=10;blockSeq=0;editClock=0;playing=null;timeForm=null;placeForm=null;deletionOrigins={};seasonMonths=copy(defaultSeasons);closeSheet();$('toast').style.display='none';rootPage('capture');
});
render();
