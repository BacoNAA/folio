// Clue identities are shared by the editor, record details and search.
const clueKinds = {person: '人物', object: '物件'};
const sampleClues = [
  {id: 'mother', kind: 'person', name: '妈妈', note: ''},
  {id: 'li-work', kind: 'person', name: '小李', note: '同事'},
  {id: 'li-school', kind: 'person', name: '小李', note: '大学同学'},
  {id: 'cup', kind: 'object', name: '搪瓷杯', note: '白底蓝边'}
];
let savedClues = sampleClues.slice();
let clueSelection = null;

function clueLabel(clue) {
  return [clue.name, clue.note].filter(Boolean).join(' · ');
}

function recordClues(record) {
  const available = savedClues.concat(record.pendingClues || []);
  return (record.clueIds || []).map(id => available.find(clue => clue.id === id)).filter(Boolean);
}

function clueButtons(record) {
  return Object.entries(clueKinds).map(([kind, label]) => {
    const names = recordClues(record).filter(clue => clue.kind === kind).map(clueLabel);
    return btn(label + '　' + esc(names.join('、') || '选择或新建'), 'pick-clues', kind);
  }).join('');
}

function openCluePicker(kind) {
  const draft = drafts[activeDraft];
  const pending = (draft.pendingClues || []).filter(clue => clue.kind === kind);
  clueSelection = {
    kind,
    ids: new Set(recordClues(draft).filter(clue => clue.kind === kind).map(clue => clue.id)),
    pending: copy(pending)
  };
  sheet('<h3>选择' + clueKinds[kind] + '</h3>' +
    '<input id="clue-query" aria-label="查找或新建线索" placeholder="输入名称或区别说明">' +
    '<div id="clue-options"></div><details class="new-clue"><summary>新建' + clueKinds[kind] + '</summary>' +
    '<label class="field" for="clue-name">名称</label><input id="clue-name">' +
    '<label class="field" for="clue-note">区别说明（选填）</label><input id="clue-note">' +
    btn('加入本次选择', 'new-clue', '', 'secondary wide') +
    '</details><div class="sheet-footer">' + btn('取消', 'close') + btn('选好了', 'apply-clues', '', 'primary') + '</div>');
  renderClueOptions();
}

function renderClueOptions() {
  const {kind, ids, pending} = clueSelection;
  const query = $('clue-query').value.trim().toLowerCase();
  const options = savedClues.concat(pending).filter(clue =>
    clue.kind === kind && clueLabel(clue).toLowerCase().includes(query));
  $('clue-options').innerHTML = options.map(clue =>
    '<label class="clue-option"><input type="checkbox" data-clue-id="' + esc(clue.id) +
    '" ' + (ids.has(clue.id) ? 'checked' : '') + '><span>' + esc(clueLabel(clue)) + '</span></label>'
  ).join('') || '<p class="hint">没有匹配的线索。</p>';
}

function createDraftClue() {
  const name = $('clue-name').value.trim();
  if (!name) { $('clue-name').focus(); return; }
  const clue = {id: 'clue-' + (++seq), kind: clueSelection.kind, name, note: $('clue-note').value.trim()};
  clueSelection.pending.push(clue);
  clueSelection.ids.add(clue.id);
  $('clue-query').value = '';
  $('clue-name').value = '';
  $('clue-note').value = '';
  $('clue-name').closest('details').open = false;
  renderClueOptions();
}

function applyClues() {
  const draft = drafts[activeDraft];
  const {kind, ids, pending} = clueSelection;
  draft.clueIds = recordClues(draft).filter(clue => clue.kind !== kind).map(clue => clue.id).concat([...ids]);
  const staged = (draft.pendingClues || []).filter(clue => clue.kind !== kind)
    .concat(pending.filter(clue => ids.has(clue.id)));
  if (staged.length) draft.pendingClues = staged;
  else delete draft.pendingClues;
  closeSheet();
  refreshEditor();
}

function clueFilters() {
  return Object.entries(clueKinds).map(([kind, label]) => {
    const options = savedClues.filter(clue => clue.kind === kind).map(clue =>
      '<label><input type="checkbox" data-multi="' + kind + '" value="' + esc(clue.id) +
      '" ' + (filters[kind].includes(clue.id) ? 'checked' : '') + '>' + esc(clueLabel(clue)) + '</label>');
    return '<details class="filter-options" data-search-panel="' + kind + '" ' +
      (searchPanels[kind] ? 'open' : '') + '><summary>' + label + '</summary>' + options.join('') + '</details>';
  }).join('');
}

function matchesClues(record) {
  return Object.keys(clueKinds).every(kind => !filters[kind].length ||
    filters[kind].some(id => record.clueIds?.includes(id)));
}
