'use strict';

const MAX_COOKIES = 128;
const MAX_BYTES = 128 * 1024;
const DOMAIN = /^\.?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)*novelpia\.com$/i;

function makeExport(cookies, nowSeconds) {
  if (!Array.isArray(cookies) || !cookies.length || cookies.length > MAX_COOKIES) {
    throw new Error('쿠키가 없거나 개수 제한(128개)을 초과했습니다.');
  }
  const result = cookies.map(cookie => {
    // Reject the entire export, rather than quietly including or ignoring unexpected domains.
    if (!cookie || typeof cookie.domain !== 'string' ||
        !DOMAIN.test(cookie.domain) ||
        typeof cookie.name !== 'string' || typeof cookie.value !== 'string' ||
        typeof cookie.path !== 'string' || !cookie.path.startsWith('/') ||
        typeof cookie.secure !== 'boolean' ||
        (cookie.expirationDate !== undefined &&
         (!Number.isFinite(cookie.expirationDate) || cookie.expirationDate < 0))) {
      throw new Error('유효하지 않거나 노벨피아 외부 도메인의 쿠키가 감지되었습니다.');
    }
    const entry = {
      name: cookie.name, value: cookie.value, domain: cookie.domain,
      path: cookie.path, secure: cookie.secure
    };
    if (cookie.expirationDate !== undefined) entry.expirationDate = cookie.expirationDate;
    return entry;
  });
  const json = JSON.stringify({format: 'novelia-session-v1', exportedAt: nowSeconds, cookies: result});
  if (new TextEncoder().encode(json).length > MAX_BYTES) {
    throw new Error('세션 파일 크기 제한(128 KiB)을 초과했습니다.');
  }
  return json;
}

// Injected into the isolated world: reads script TEXT, never runs page code or
// returns page content/member IDs to the extension.
function pageLoginState() {
  for (const script of document.scripts) {
    const text = script.textContent || '';
    const marker = /\bconst\s+_top_obj\s*=\s*\{\s*data\s*:\s*\{([^{}]*)\}/g;
    for (const match of text.matchAll(marker)) {
      const ids = [...match[1].matchAll(/(?:^|,)\s*mem_no\s*:\s*(?:"([^"]*)"|'([^']*)'|(\d+)|null)\s*(?=,|$)/g)];
      if (ids.length !== 1) return 'unknown';
      const id = ids[0][1] ?? ids[0][2] ?? ids[0][3];
      if (id === undefined || id === '' || /^0+$/.test(id)) return 'unauthenticated';
      return /^[1-9]\d*$/.test(id) ? 'authenticated' : 'unknown';
    }
  }
  return 'unknown';
}

function officialTab(tab) {
  try {
    const url = new URL(tab?.url);
    return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
      (url.hostname === 'novelpia.com' || url.hostname === 'book.novelpia.com') &&
      Number.isInteger(tab.id);
  } catch {
    return false;
  }
}

async function exportOnClick(api, now = () => Math.floor(Date.now() / 1000),
                             urlApi = URL) {
  const [tab] = await api.tabs.query({active: true, currentWindow: true});
  if (!officialTab(tab)) throw new Error('공식 노벨피아 탭을 활성화한 뒤 다시 시도하세요.');
  const stores = await api.cookies.getAllCookieStores();
  const store = stores.find(item => item.tabIds.includes(tab.id));
  if (!store) throw new Error('현재 탭의 쿠키 저장소를 찾을 수 없습니다.');
  const results = await api.scripting.executeScript({target: {tabId: tab.id}, func: pageLoginState});
  if (results.length !== 1 || results[0].frameId !== 0 ||
      results[0].result !== 'authenticated') {
    throw new Error('브라우저 로그인을 확인할 수 없습니다. 공식 사이트에서 로그인 후 새로고침하세요.');
  }
  const cookies = await api.cookies.getAll({domain: 'novelpia.com', storeId: store.id});
  const json = makeExport(cookies, now());
  const url = urlApi.createObjectURL(new Blob([json], {type: 'application/json'}));
  let downloadId;
  let settled = false;
  // A terminal event can arrive before downloads.download resolves with its ID.
  const terminalIds = new Set();
  const cleanup = () => {
    if (settled) return;
    settled = true;
    api.downloads.onChanged.removeListener(onChanged);
    urlApi.revokeObjectURL(url);
  };
  const onChanged = delta => {
    if (delta.state?.current !== 'complete' && delta.state?.current !== 'interrupted') return;
    if (downloadId === undefined) terminalIds.add(delta.id);
    else if (delta.id === downloadId) cleanup();
  };
  api.downloads.onChanged.addListener(onChanged);
  try {
    downloadId = await api.downloads.download({
      url, filename: 'novelia-session.json', saveAs: true
    });
    if (terminalIds.has(downloadId)) cleanup();
    return {downloadId, cookieCount: cookies.length};
  } catch (error) {
    cleanup();
    throw error;
  }
}

if (typeof document !== 'undefined') {
  const button = document.getElementById('export');
  const status = document.getElementById('status');
  button.addEventListener('click', async () => {
    button.disabled = true;
    status.textContent = '브라우저 로그인 확인 중…';
    try {
      const {cookieCount} = await exportOnClick(chrome);
      status.textContent = `브라우저 로그인 확인됨 · 쿠키 ${cookieCount}개. 파일 저장을 확인하세요. Android 앱에서 로그인 상태를 별도로 확인해야 합니다. 가져온 뒤 파일을 삭제하세요.`;
    } catch (error) {
      // Never display raw API errors, which could contain sensitive information.
      status.textContent = error.message === '브라우저 로그인을 확인할 수 없습니다. 공식 사이트에서 로그인 후 새로고침하세요.' ||
        error.message === '공식 노벨피아 탭을 활성화한 뒤 다시 시도하세요.'
        ? error.message : '내보내기에 실패했습니다. 탭, 쿠키 개수 및 파일 크기를 확인하세요.';
    } finally {
      button.disabled = false;
    }
  });
}

if (typeof module !== 'undefined' && module.exports) module.exports = {makeExport, exportOnClick, pageLoginState, officialTab};
