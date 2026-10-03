'use strict';
const assert = require('node:assert/strict');
const {makeExport, exportOnClick, pageLoginState, officialTab} = require('./popup.js');

function classify(text) {
  const previous = global.document;
  global.document = {scripts: [{textContent: text}]};
  try { return pageLoginState(); } finally { global.document = previous; }
}
assert.equal(classify('const _top_obj={data:{mem_no:"123",name:"a"}};'), 'authenticated');
assert.equal(classify("const _top_obj={data:{mem_no:'0'}};"), 'unauthenticated');
assert.equal(classify('const _top_obj={data:{mem_no:null}};'), 'unauthenticated');
assert.equal(classify('login_req login_box'), 'unknown');
assert.equal(classify('const _top_obj={data:{mem_no:"abc"}};'), 'unknown');
assert.equal(classify('const _top_obj = {\n data : {\n mem_adt : "0",\n'
  + 'mem_no : "42",\n mem_birthday : "2000-01-01",\n }, methods : {} };'), 'authenticated');
assert.equal(classify('const other={mem_no:"123"};'), 'unknown');
assert.equal(classify('const _top_obj={data:{mem_no:"0",mem_no:"42"}};'), 'unknown');
const manifest = require('./manifest.json');
assert(manifest.host_permissions.includes('https://*.novelpia.com/*'),
  'subdomain cookies must remain accessible even though active tabs are restricted');
assert.equal(manifest.host_permissions.some(value => value.includes('google')), false);
for (const url of ['https://google.com/', 'https://x.novelpia.com/',
                   'https://user:pass@novelpia.com/', 'https://novelpia.com:8443/',
                   'http://novelpia.com/']) assert.equal(officialTab({id: 7, url}), false);
for (const url of ['https://novelpia.com/', 'https://book.novelpia.com/'])
  assert.equal(officialTab({id: 7, url}), true);

const valid = {name: 'session', value: 'secret', domain: '.novelpia.com', path: '/', secure: true};
const json = JSON.parse(makeExport([valid], 123));
assert.deepEqual(json, {format: 'novelia-session-v1', exportedAt: 123, cookies: [valid]});
for (const domain of ['notnovelpia.com', 'novelpia.com.evil.test', '-a.novelpia.com',
                      'a-.novelpia.com', 'a..novelpia.com', '.-a.novelpia.com']) {
  assert.throws(() => makeExport([{...valid, domain}], 123), domain);
}
assert.deepEqual(JSON.parse(makeExport([{...valid, domain: 'a-b.novelpia.com'}], 123)).cookies[0].domain, 'a-b.novelpia.com');
assert.throws(() => makeExport(Array(129).fill(valid), 123));
assert.throws(() => makeExport([{...valid, value: 'x'.repeat(128 * 1024)}], 123));
assert.deepEqual(JSON.parse(makeExport([{...valid, domain: 'reader.novelpia.com', expirationDate: 999}], 123)).cookies[0].expirationDate, 999);

(async () => {
  let reads = 0;
  let downloads = 0;
  let revoked = [];
  const blobs = [];
  const listeners = new Set();
  const urlApi = {
    createObjectURL: blob => { assert.equal(blob.type, 'application/json'); blobs.push(blob); return 'blob:mock-' + (downloads + 1); },
    revokeObjectURL: url => revoked.push(url)
  };
  let shouldFail = false;
  let earlyTerminal = false;
  let activeUrl = 'https://book.novelpia.com/';
  let state = 'authenticated';
  let availableCookies = [valid];
  const emit = delta => { for (const listener of [...listeners]) listener(delta); };
  const api = {
    tabs: {query: async filter => {
      assert.deepEqual(filter, {active: true, currentWindow: true});
      return [{id: 7, url: activeUrl}];
    }},
    scripting: {executeScript: async options => {
      assert.deepEqual(options.target, {tabId: 7});
      assert.equal(options.func, pageLoginState);
      return [{frameId: 0, result: state}];
    }},
    cookies: {
      getAllCookieStores: async () => [{id: 'other', tabIds: [8]}, {id: 'selected', tabIds: [7]}],
      getAll: async filter => {
        reads++;
        assert.deepEqual(filter, {domain: 'novelpia.com', storeId: 'selected'});
        return availableCookies;
      }
    },
    downloads: {
      onChanged: {addListener: listener => listeners.add(listener), removeListener: listener => listeners.delete(listener)},
      download: async options => {
        downloads++;
        assert.equal(options.filename, 'novelia-session.json');
        assert.equal(options.saveAs, true);
        assert.equal(options.url, 'blob:mock-' + downloads);
        if (shouldFail) throw new Error('cancelled');
        if (earlyTerminal) emit({id: downloads, state: {current: 'complete'}});
        return downloads;
      }
    }
  };
  assert.equal(reads, 0); // Importing the module never reads cookies.
  assert.equal(downloads, 0);
  assert.deepEqual(await exportOnClick(api, () => 123, urlApi), {downloadId: 1, cookieCount: 1});
  assert.equal(reads, 1);
  assert.deepEqual(JSON.parse(await blobs[0].text()), json);
  assert.equal(listeners.size, 1);
  assert.deepEqual(revoked, []); // Do not revoke merely because download() returned.
  emit({id: 999, state: {current: 'complete'}});
  emit({id: 1, state: {current: 'in_progress'}});
  assert.deepEqual(revoked, []);
  emit({id: 1, state: {current: 'complete'}});
  assert.deepEqual(revoked, ['blob:mock-1']);
  assert.equal(listeners.size, 0);

  assert.deepEqual(await exportOnClick(api, () => 123, urlApi), {downloadId: 2, cookieCount: 1});
  emit({id: 2, state: {current: 'interrupted'}});
  assert.deepEqual(revoked, ['blob:mock-1', 'blob:mock-2']);
  assert.equal(listeners.size, 0);

  earlyTerminal = true;
  assert.deepEqual(await exportOnClick(api, () => 123, urlApi), {downloadId: 3, cookieCount: 1});
  assert.deepEqual(revoked, ['blob:mock-1', 'blob:mock-2', 'blob:mock-3']);
  assert.equal(listeners.size, 0);
  earlyTerminal = false;

  shouldFail = true;
  await assert.rejects(exportOnClick(api, () => 123, urlApi));
  assert.deepEqual(revoked, ['blob:mock-1', 'blob:mock-2', 'blob:mock-3', 'blob:mock-4']);
  assert.equal(listeners.size, 0);
  availableCookies = [{...valid, domain: 'google.com'}];
  await assert.rejects(exportOnClick(api, () => 123, urlApi));
  availableCookies = [];
  await assert.rejects(exportOnClick(api, () => 123, urlApi));
  const readsBefore = reads;
  for (const badState of ['unknown', 'unauthenticated']) {
    state = badState;
    await assert.rejects(exportOnClick(api, () => 123, urlApi), /브라우저 로그인을 확인할 수 없습니다/);
  }
  state = 'authenticated';
  activeUrl = 'https://google.com/';
  await assert.rejects(exportOnClick(api, () => 123, urlApi), /공식 노벨피아 탭/);
  assert.equal(reads, readsBefore);
  activeUrl = 'https://novelpia.com/';
  api.cookies.getAllCookieStores = async () => [{id: 'other', tabIds: [8]}];
  await assert.rejects(exportOnClick(api, () => 123, urlApi), /쿠키 저장소/);
  assert.equal(downloads, 4);
  assert.equal(listeners.size, 0);
})().catch(error => { console.error(error); process.exitCode = 1; });
