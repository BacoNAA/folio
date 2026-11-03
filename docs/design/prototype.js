'use strict';
// Design prototype: fictional data, memory only, no device or network access.
const copy = value => JSON.parse(JSON.stringify(value));
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const initialPlaces = [{id:'p1',name:'巷口咖啡',city:'杭州 · 老街',position:null},{id:'p2',name:'西湖',city:'杭州',position:null}];
const initialRecords = [
 {id:'r1',title:'秋天的第一杯热咖啡',created:'2026-11-03',time:{kind:'date',value:'2026-11-02'},placeId:'p1',tags:['日常','值得再去'],star:true,pin:false,person:'',object:'',blocks:[{type:'text',text:'靠窗的位置，能看见梧桐叶慢慢落下来。今天的拿铁有一点坚果香。'},{type:'photo',caption:'窗边的下午'}]},
 {id:'r2',title:'西湖边的那阵风',created:'2026-11-02',time:{kind:'date',value:'2026-10-26'},placeId:'p2',tags:['散步'],star:false,pin:false,person:'',object:'',blocks:[{type:'text',text:'沿着湖边走了一小段，想记住风吹过树叶的声音。'},{type:'audio',caption:'湖边，傍晚',duration:'00:18'}]},
 {id:'r3',title:'妈妈的红豆汤',created:'2026-11-01',time:{kind:'free',value:'小时候'},placeId:null,tags:['家常'],star:true,pin:false,person:'妈妈',object:'',blocks:[{type:'text',text:'放学回家，桌上总有一碗。后来自己煮，总觉得还差一点。'},{type:'checklist',items:[{text:'红豆提前浸泡',done:true},{text:'最后再加糖',done:false}]}]}
];
let records=copy(initialRecords), places=copy(initialPlaces), drafts={}, trash=[], route='home', selected=null, activeDraft=null, placeId=null, sort='event', returnRoute='home', seq=10, toastTimer;
const filters={q:'',media:'',tag:'',year:'',star:false};
const $ = id => document.getElementById(id);
const availableDrafts = () => Object.entries(drafts).filter(([key]) => key==='new'||records.some(r=>r.id===key));
const btn = (text,action,id='',cls='') => `<button class="${cls}" data-action="${action}" data-id="${esc(id)}">${text}</button>`;
const placeFor = record => record.pendingPlace || places.find(p=>p.id===record.placeId);
const timeLabel = time => time.kind==='none' ? '时间未定' : time.kind==='year' ? `${time.value} 年` : time.value;
const searchable = r => [r.title,...r.blocks.map(b=>b.text||b.caption||(b.items||[]).map(i=>i.text).join(' ')),r.tags.join(' '),placeFor(r)?.name,r.person,r.object].join(' ').toLowerCase();
const matches = r => searchable(r).includes(filters.q.toLowerCase()) && (!filters.star||r.star) && (!filters.media||r.blocks.some(b=>b.type===filters.media)) && (!filters.tag||r.tags.includes(filters.tag)) && (!filters.year || (filters.year==='unknown' ? ['none','free'].includes(r.time.kind) : ['date','year'].includes(r.time.kind)&&r.time.value.startsWith(filters.year)));
function header(title,tools='',subtitle='') {
 $('header').innerHTML=`<div class="topline"><h2>${title}</h2><div class="tools">${tools}</div></div>${subtitle?`<p class="subtitle">${subtitle}</p>`:''}`;
}
function compact(title,tools='') {
 $('header').innerHTML=`<div class="topline">${btn('‹ 返回','back','','back')}<h2 class="compact-title">${title}</h2><div class="tools">${tools}</div></div>`;
}
function photo(caption,small=false){return `<div class="photo ${small?'small':''}" role="img" aria-label="示意照片"><span>照片示意 · ${esc(caption||'未填写说明')}</span></div>`;}
function card(r){
 const p=placeFor(r), text=r.blocks.find(b=>b.type==='text'), image=r.blocks.find(b=>b.type==='photo');
 return `<article class="record">${btn(`<div class="meta"><span>${esc(timeLabel(r.time))}</span>${r.pin?'<span>置顶</span>':''}${r.star?'<span>★</span>':''}</div><h3>${esc(r.title||'一条记录')}</h3>${text?`<p>${esc(text.text)}</p>`:''}${image?photo(image.caption):''}<div class="meta">${p?`<span>⌖ ${esc(p.name)}</span>`:''}${r.blocks.some(b=>b.type==='audio')?'<span>◖ 声音</span>':''}${r.blocks.some(b=>b.type==='checklist')?'<span>☑ 清单</span>':''}${r.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')}</div>`,'open',r.id,'record-open')}</article>`;
}
function list(items){return items.length?items.map(card).join(''):'<div class="empty">这里暂时没有记录。<br>可以换一个条件再找找。</div>';}
function sorted(items){return [...items].sort((a,b)=>Number(b.pin)-Number(a.pin)|| (sort==='created'?b.created.localeCompare(a.created):eventKey(b).localeCompare(eventKey(a))));}
function eventKey(r){return ['date','year'].includes(r.time.kind)?r.time.value:'';}
function render(){
 $('bottom').innerHTML='';
 if(route==='home') home();
 if(route==='detail') detail();
 if(route==='edit') editor();
 if(route==='search') search();
 if(route==='places') placeList();
 if(route==='place') booklet();
 if(route==='trash') recycle();
 if(['home','places'].includes(route)) $('bottom').innerHTML=`<nav class="navigation" aria-label="主导航">${btn('▤　记录','home','',route==='home'?'active':'')}${btn('+','new','','new')}${btn('⌖　地点','places','',route==='places'?'active':'')}</nav>`;
}
function home(){
 header('生活的片页',btn('搜索','search')+btn('更多','more'),'随手留下的，都在这里。');
 const known=sorted(records.filter(r=>r.time.kind==='date')), ranges=records.filter(r=>r.time.kind==='year'), unknown=records.filter(r=>!eventKey(r));
 $('screen').innerHTML=`<div class="segment">${btn('事件时间','sort','event',sort==='event'?'active':'')}${btn('最近记录','sort','created',sort==='created'?'active':'')}</div>${availableDrafts().length?btn(`有 ${availableDrafts().length} 份未完成草稿 <span>继续 ›</span>`,'drafts','','draft-link'):''}${sort==='created'?list(sorted(records)):`<div class="section-label">记得的日子 <span>${records.length} 条记录</span></div>${known.length?list(known):records.length?'':list([])}${ranges.length?'<div class="section-label">范围时间 · 不强排精确先后</div>'+list(ranges):''}${unknown.length?'<div class="section-label">时间未定 · 保留原来的说法</div>'+list(unknown):''}`}`;
}
function detail(){
 const r=records.find(r=>r.id===selected); if(!r){route='home';return render();}
 compact('记录',btn('编辑','edit',r.id));
 $('screen').innerHTML=`<article class="detail"><div class="meta">${esc(timeLabel(r.time))}</div><h2>${esc(r.title||'一条记录')}</h2>${r.blocks.map(b=>b.type==='text'?`<p>${esc(b.text)}</p>`:b.type==='photo'?photo(b.caption):b.type==='audio'?`<div class="audio">${btn('▷','play')}<i aria-hidden="true">▂ ▅ ▃ ▆ ▂ ▄ ▆</i><span>${b.duration}</span></div><div class="hint">${esc(b.caption)}</div>`:`<div>${b.items.map(i=>`<div class="check"><span>${i.done?'☑':'☐'}</span><span>${esc(i.text)}</span></div>`).join('')}</div>`).join('')}<div class="meta">${r.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')}${placeFor(r)?btn(`⌖ ${esc(placeFor(r).name)}`,'place',r.placeId):''}</div><div class="hint">记录于 ${r.created}${r.modified?' · 已修改':''}</div><div class="detail-actions">${btn(r.star?'★ 已加星':'☆ 加星','star',r.id)}${btn(r.pin?'取消置顶':'置顶','pin',r.id)}${btn('删除','delete',r.id,'danger')}</div></article>`;
}
function startEdit(id){
 activeDraft=id||'new';
 if(!drafts[activeDraft]) drafts[activeDraft]=id?copy(records.find(r=>r.id===id)):{id:'r'+(++seq),title:'',created:'2026-11-03',time:{kind:'date',value:'2026-11-03'},placeId:placeId&&route==='place'?placeId:null,tags:[],star:false,pin:false,person:'',object:'',blocks:[{type:'text',text:''}]};
 route='edit';render();
}
function editor(){
 const d=drafts[activeDraft]; if(!d){route='home';return render();}
 compact('记录这一刻',btn('完成','finish','','primary'));
 $('screen').innerHTML=`<input class="title-input" aria-label="标题（选填）" placeholder="标题，留空也可以" data-field="title" value="${esc(d.title)}">${d.blocks.map((b,i)=>`<section class="block"><div class="block-toolbar"><span>${{text:'文字',photo:'照片',audio:'声音',checklist:'清单'}[b.type]}</span>${btn('移除','remove-block',i)}</div>${b.type==='text'?`<textarea aria-label="文字内容" placeholder="写下一点此刻……" data-block="${i}">${esc(b.text)}</textarea>`:b.type==='photo'?`${photo(b.caption,true)}<input aria-label="照片说明" data-caption="${i}" placeholder="照片说明（选填）" value="${esc(b.caption)}">`:b.type==='audio'?`<div class="audio">${btn('▷','play')}<i aria-hidden="true">▂ ▅ ▃ ▆ ▂ ▄</i><span>${b.duration}</span></div><input aria-label="声音说明" data-caption="${i}" placeholder="声音说明（选填）" value="${esc(b.caption)}">`:`${b.items.map((item,j)=>`<label class="check"><input type="checkbox" aria-label="事项 ${j+1} 完成" data-check="${i}:${j}" ${item.done?'checked':''}><input type="text" aria-label="清单事项 ${j+1}" data-item="${i}:${j}" value="${esc(item.text)}" placeholder="一件小事"></label>`).join('')}${btn('＋ 添加事项','add-item',i)}`}</section>`).join('')}${btn('＋ 添加内容','add','','add-content')}<details class="clues"><summary>时间、地点与其他线索 <span class="hint">选填</span></summary>${btn(`时间　${esc(timeLabel(d.time))}`,'time')}${btn(`地点　${esc(placeFor(d)?.name||'选择或新建')}`,'pick-place')}<label class="field" for="tags">文字标签（用逗号分开）</label><input id="tags" data-field="tags" value="${esc(d.tags.join('，'))}"><label class="field" for="person">人物线索（此样稿演示一位）</label><input id="person" data-field="person" value="${esc(d.person)}"><label class="field" for="object">物件线索（此样稿演示一件）</label><input id="object" data-field="object" value="${esc(d.object)}"></details>`;
 $('bottom').innerHTML=`<div class="editor-bottom"><span class="saved">草稿保留在本次预览中</span>${btn('放弃修改','discard','','danger')}</div>`;
}
function search(){
 compact('找回一段生活');
 const tags=[...new Set(records.flatMap(r=>r.tags))];
 $('screen').innerHTML=`<input id="query" class="searchbox" aria-label="搜索记录" placeholder="文字、地点，或一个人的名字" value="${esc(filters.q)}"><div class="filters"><select aria-label="内容类型" data-filter="media"><option value="">所有内容</option>${[['text','文字'],['photo','照片'],['audio','录音'],['checklist','清单']].map(([v,t])=>`<option value="${v}" ${filters.media===v?'selected':''}>${t}</option>`).join('')}</select><select aria-label="文字标签" data-filter="tag"><option value="">所有标签</option>${tags.map(t=>`<option ${filters.tag===t?'selected':''}>${esc(t)}</option>`).join('')}</select><select aria-label="事件时间筛选" data-filter="year"><option value="">所有时间</option>${['2026','2016','unknown'].map(v=>`<option value="${v}" ${filters.year===v?'selected':''}>${v==='unknown'?'时间未定':v+' 年'}</option>`).join('')}</select><label class="filter-star"><input type="checkbox" data-filter="star" ${filters.star?'checked':''}>只看星标</label></div><div id="results"></div>`;
 results();
}
function results(){const found=records.filter(matches);$('results').innerHTML=`<div class="section-label">${found.length} 条记录 ${btn('清除条件','clear-filters')}</div>${found.map(card).join('')||'<div class="empty">没有找到匹配内容。<br>试试减少筛选条件。</div>'}`;}
function placeList(){header('走过的地方',btn('回收站','trash'),'记得名字，也能找到那一天。');$('screen').innerHTML=`<input id="place-query" aria-label="查找地点" placeholder="查找地点、城市或地址"><div id="place-results"></div>`;placeResults('');}
function placeResults(query){$('place-results').innerHTML=places.filter(p=>(p.name+p.city).includes(query)).map(p=>btn(`<span class="place-icon">⌖</span><span><strong>${esc(p.name)}</strong><small>${esc(p.city||'未填写城市')} · ${records.filter(r=>r.placeId===p.id).length} 条记录</small></span>`,'place',p.id,'place-row')).join('')||'<div class="empty">没有找到这个地点。</div>';}
function booklet(){const p=places.find(p=>p.id===placeId);if(!p){route='places';return render();}compact('地点小册');$('screen').innerHTML=`<section class="place-intro"><div class="eyebrow">地 点 小 册</div><h2>${esc(p.name)}</h2><p>${esc(p.city||'城市未填写')}<br>${p.position?'已附示例坐标':'未附位置，照样可以找回记录'}</p>${btn('在这里记一条','new','','secondary wide')}</section>${list(records.filter(r=>r.placeId===p.id))}`;}
function recycle(){compact('回收站');$('screen').innerHTML=`<p class="hint">保留到你手动清理。恢复后仍可继续查看和编辑。</p>${trash.length?trash.map(r=>`<article class="trash-item"><h3>${esc(r.title||'一条记录')}</h3><div class="meta">${esc(timeLabel(r.time))}</div><div class="tools">${btn('恢复','restore',r.id)}${btn('彻底删除','erase',r.id,'danger')}</div></article>`).join(''):'<div class="empty">回收站是空的。</div>'}`;}
function sheet(content){$('sheet-content').innerHTML=content;if(!$('sheet').open)$('sheet').showModal();}
function closeSheet(){$('sheet').close();}
function message(text,action='',id=''){clearTimeout(toastTimer);$('toast').innerHTML=`<span>${esc(text)}</span>${action?btn('撤销',action,id):''}`;$('toast').style.display='flex';toastTimer=setTimeout(()=>$('toast').style.display='none',6000);}
function placePicker(){sheet(`<h3>选择一个地点</h3><input id="pick-query" aria-label="搜索或新建地点" placeholder="搜索已有地点，或填写新名字"><div id="pick-results"></div><div class="sheet-footer">${btn('取消','close')}${btn('新建此地点','create-place','','primary')}</div>`);pickResults('');}
function pickResults(q){$('pick-results').innerHTML=`<div class="sheet-options">${places.filter(p=>(p.name+p.city).includes(q)).map(p=>btn(`${esc(p.name)} <span class="hint">${esc(p.city)}</span>`,'choose-place',p.id)).join('')}${btn('不附地点','choose-place','')}</div>`;}
function timePicker(){const t=drafts[activeDraft].time;sheet(`<h3>事情发生在什么时候</h3><select id="time-kind" aria-label="时间填写方式">${[['date','精确日期'],['year','只记得年份'],['free','保留原来的说法'],['none','暂不填写']].map(([v,n])=>`<option value="${v}" ${t.kind===v?'selected':''}>${n}</option>`).join('')}</select><label class="field" for="time-value">日期填 2026-11-03；年份填 2016；描述可填“小时候”</label><input id="time-value" value="${esc(t.value||'')}" aria-label="事件时间"><p class="hint">记录时间会自动保留，无需填写。</p><div id="time-error" class="hint danger" role="alert"></div><div class="sheet-footer">${btn('取消','close')}${btn('使用这个时间','set-time','','primary')}</div>`);}
function finish(){const d=drafts[activeDraft];const blocks=d.blocks.filter(b=>b.type==='text'?b.text.trim():b.type==='checklist'?b.items.some(i=>i.text.trim()):true);if(!blocks.length){message('先留下一点内容，再点完成。');return;}const r=copy({...d,blocks});if(r.pendingPlace){places.push(copy(r.pendingPlace));r.placeId=r.pendingPlace.id;delete r.pendingPlace;}const index=records.findIndex(item=>item.id===r.id);if(index<0)records.unshift(r);else{r.modified=true;records[index]=r;}delete drafts[activeDraft];selected=r.id;route='detail';returnRoute='home';render();message('已完成，本次预览中的各入口已更新。');}
const actions={
 home:()=>{route='home';render();},places:()=>{route='places';render();},search:()=>{route='search';render();},trash:()=>{route='trash';render();},
 new:()=>startEdit(), edit:id=>startEdit(id),
 open:id=>{returnRoute=route;selected=id;route='detail';render();},place:id=>{placeId=id;route='place';render();},
 back:()=>{route=route==='detail'?returnRoute:route==='place'?'places':'home';render();},
 sort:id=>{sort=id;render();},finish,close:closeSheet,
 more:()=>sheet(`<h3>整理与管理</h3><div class="sheet-options">${btn('回收站','go-trash')}</div><div class="sheet-footer">${btn('关闭','close')}</div>`),
 'go-trash':()=>{closeSheet();route='trash';render();},
 drafts:()=>sheet(`<h3>继续未完成的内容</h3><div class="sheet-options">${availableDrafts().map(([key,d])=>btn(esc(d.title||'未命名草稿'),'resume',key)).join('')}</div><div class="sheet-footer">${btn('关闭','close')}</div>`),
 resume:key=>{activeDraft=key;closeSheet();route='edit';render();},
 add:()=>sheet(`<h3>添加一点内容</h3><div class="sheet-options">${btn('文字','add-block','text')}${btn('照片示例','add-block','photo')}${btn('录音示例','record-demo')}${btn('可勾选清单','add-block','checklist')}</div><div class="sheet-footer">${btn('取消','close')}</div>`),
 'add-block':type=>{drafts[activeDraft].blocks.push(type==='text'?{type,text:''}:type==='photo'?{type,caption:''}:type==='audio'?{type,caption:'',duration:'00:12'}:{type,items:[{text:'',done:false}]});closeSheet();editor();},
 'record-demo':()=>sheet(`<h3>录音交互示意</h3><p>此样稿不访问麦克风。停止后加入一段示例声音，用于查看它与文字、照片共存的布局。</p><div class="sheet-footer">${btn('取消','close')}${btn('停止并保留示例','add-block','audio','primary')}</div>`),
 play:()=>message('声音播放位置示意；原型未录制或播放音频。'),
 'remove-block':i=>{drafts[activeDraft].blocks.splice(Number(i),1);editor();},
 'add-item':i=>{drafts[activeDraft].blocks[i].items.push({text:'',done:false});editor();},
 discard:()=>sheet(`<h3>放弃这份修改？</h3><p>${activeDraft==='new'?'这份新建草稿将被移除。':'保留上一次完成的记录，移除本次未完成修改。'}</p><div class="sheet-footer">${btn('继续编辑','close')}${btn('放弃修改','confirm-discard','','danger')}</div>`),
 'confirm-discard':()=>{delete drafts[activeDraft];closeSheet();route='home';render();},
 time:timePicker,
 'set-time':()=>{const kind=$('time-kind').value,value=$('time-value').value.trim();if((kind==='date'&&(!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value))||(kind==='year'&&!/^[1-9]\d{3}$/.test(value))||(kind==='free'&&!value)){$('time-error').textContent='请填写有效日期、四位年份或时间描述。';return;}drafts[activeDraft].time={kind,value:kind==='none'?'':value};closeSheet();editor();},
 'pick-place':placePicker,
 'choose-place':id=>{const d=drafts[activeDraft];d.placeId=id||null;delete d.pendingPlace;closeSheet();editor();},
 'create-place':()=>{const name=$('pick-query').value.trim();if(!name){message('先填写地点名称。');return;}sheet(`<h3>新建地点</h3><p>${esc(name)} · 同名也会保留为独立地点</p><input id="new-place-name" type="hidden" value="${esc(name)}"><label class="field" for="new-place-city">城市或区别说明（选填）</label><input id="new-place-city"><p id="position-note" class="hint">坐标选填，不影响保存地点。</p>${btn('获取当前位置（模拟）','get-position','','secondary wide')}<input id="new-position" type="hidden" value=""><div class="sheet-footer">${btn('取消','close')}${btn('选用这个新地点','use-new-place','','primary')}</div>`);},
 'get-position':()=>{$('position-note').innerHTML=`示例位置：30.25, 120.15。<br>仅演示主动获取后确认，不代表当前位置。${btn('确认附上示例位置','attach-position','','secondary wide')}`;},
 'attach-position':()=>{$('new-position').value='demo';$('position-note').textContent='已附示例位置，提交记录时一起保存。';},
 'use-new-place':()=>{const d=drafts[activeDraft];d.pendingPlace={id:'p'+(++seq),name:$('new-place-name').value,city:$('new-place-city').value.trim(),position:$('new-position').value||null};d.placeId=d.pendingPlace.id;closeSheet();editor();},
 star:id=>{const r=records.find(r=>r.id===id);r.star=!r.star;if(drafts[id])drafts[id].star=r.star;detail();},
 pin:id=>{const r=records.find(r=>r.id===id);r.pin=!r.pin;if(drafts[id])drafts[id].pin=r.pin;detail();},
 delete:id=>{const index=records.findIndex(r=>r.id===id);trash.push(records.splice(index,1)[0]);route='home';render();message('记录已移入回收站。','restore',id);},
 restore:id=>{const index=trash.findIndex(r=>r.id===id);if(index<0)return;const r=trash.splice(index,1)[0];if(r.placeId&&!places.some(p=>p.id===r.placeId))r.placeId=null;records.push(r);$('toast').style.display='none';render();},
 erase:id=>sheet(`<h3>彻底删除这条记录？</h3><p>彻底删除后，无法从回收站恢复。</p><div class="sheet-footer">${btn('取消','close')}${btn('彻底删除','confirm-erase',id,'danger')}</div>`),
 'confirm-erase':id=>{trash=trash.filter(r=>r.id!==id);delete drafts[id];closeSheet();render();},
 'clear-filters':()=>{Object.assign(filters,{q:'',media:'',tag:'',year:'',star:false});search();}
};
document.addEventListener('click',event=>{const target=event.target.closest('button');if(!target)return;if(target.dataset.action)actions[target.dataset.action]?.(target.dataset.id);if(target.dataset.route){const next=target.dataset.route;if(next==='edit')startEdit();else{route=next;render();}}});
document.addEventListener('input',event=>{
 const el=event.target,d=drafts[activeDraft];
 if(el.id==='query'){filters.q=el.value;results();}
 if(el.id==='place-query')placeResults(el.value);
 if(el.id==='pick-query')pickResults(el.value);
 if(route!=='edit'||!d)return;
 if(el.dataset.field)d[el.dataset.field]=el.dataset.field==='tags'?el.value.split(/[,，]/).map(t=>t.trim()).filter(Boolean):el.value;
 if(el.dataset.block!==undefined)d.blocks[el.dataset.block].text=el.value;
 if(el.dataset.caption!==undefined)d.blocks[el.dataset.caption].caption=el.value;
 if(el.dataset.item){const [i,j]=el.dataset.item.split(':');d.blocks[i].items[j].text=el.value;}
 if(el.dataset.check){const [i,j]=el.dataset.check.split(':');d.blocks[i].items[j].done=el.checked;}
});
document.addEventListener('change',event=>{const el=event.target;if(el.dataset.filter){filters[el.dataset.filter]=el.type==='checkbox'?el.checked:el.value;results();}});
$('reset').addEventListener('click',()=>{records=copy(initialRecords);places=copy(initialPlaces);drafts={};trash=[];route='home';activeDraft=null;placeId=null;Object.assign(filters,{q:'',media:'',tag:'',year:'',star:false});closeSheet();$('toast').style.display='none';render();});
render();
