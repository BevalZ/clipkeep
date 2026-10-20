// Run with playwright-cli run-code --filename ui/checks/virtual-list.js against the Vite dev server.
async (page) => {
  await page.route('**/src/main.ts*', async route => {
    const response = await route.fetch();
    const source = await response.text();
    await route.fulfill({response, body: `
      import { mockIPC, mockWindows } from '/node_modules/@tauri-apps/api/mocks.js';
      mockWindows('main');
      window.testCalls = 0;
      mockIPC(async (cmd, args) => {
        if (cmd === 'plugin:window|is_visible') return true;
        if (cmd === 'list_clips') { window.testCalls++; await new Promise(r => setTimeout(r, 40)); return Array.from({length: 1000}, (_, i) => ({id: i + 1, kind: 'text', preview: '记录 ' + (i + 1) + '\\n第二行内容\\n第三行内容', size: 100, truncated: true, favorite: false, pinned: false, updatedAt: Date.now(), source: null, groupId: null, thumb: null})); }
        if (cmd === 'list_groups') return [];
        if (cmd === 'get_settings') return {settings: {keepOnBlur: false, theme: 'light', maxStorageMb: 500, hotkeyEnabled: true, hotkey: 'win-v', captureImages: true}, hotkeys: [], dataDir: 'mock'};
        if (cmd === 'get_lan_state') return {receiveEnabled: false, peers: [], localCodes: []};
        if (cmd === 'get_storage_usage') return {ordinaryBytes: 0, protectedBytes: 0, databaseBytes: 0};
        if (cmd === 'get_clip_text') return 'x'.repeat(100000);
        return false;
      }, {shouldMockEvents: true});
      ${source}`});
  });

  await page.setViewportSize({width: 400, height: 600});
  await page.reload();
  await page.locator('.item').first().waitFor();
  if (await page.locator('.item').count() > 20) throw new Error('List is not virtualized');
  await page.getByRole('textbox', {name:'搜索文本…'}).focus();
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(() => document.querySelector('.item.selected .text')?.textContent?.startsWith('记录 2\n'));
  const before = await page.evaluate(() => window.testCalls);
  await page.evaluate(async () => { for (let i = 0; i < 30; i++) await window.__TAURI_INTERNALS__.invoke('plugin:event|emit', {event:'clips-changed'}); });
  await page.waitForTimeout(200);
  const burst = await page.evaluate(() => window.testCalls);
  if (burst - before > 2) throw new Error('Unbounded reload burst');
  await page.locator('.list').evaluate(el => el.scrollTop=el.scrollHeight);
  await page.getByText('记录 1000',{exact:false}).waitFor();
  await page.getByRole('button',{name:'设置',exact:true}).click();
  await page.getByRole('button',{name:'返回 (Esc)'}).waitFor();
  await page.evaluate(() => window.__TAURI_INTERNALS__.invoke('plugin:event|emit', {event:'clips-changed'}));
  await page.getByRole('button',{name:'返回 (Esc)'}).click();
  await page.getByText('记录 1000',{exact:false}).waitFor();
  await page.waitForTimeout(150);
  const restored = await page.locator('.list').evaluate(el => el.scrollTop);
  if (restored<100000) throw new Error('Settings lost scroll');
  await page.evaluate(async () => {
    await window.__TAURI_INTERNALS__.invoke('plugin:event|emit',{event:'clips-changed'});
    await window.__TAURI_INTERNALS__.invoke('plugin:event|emit',{event:'window-hidden'});
  });
  await page.waitForTimeout(150);
  if (await page.locator('.item').count() !== 0) throw new Error('Late response repopulated hidden list');
  const hiddenCalls = await page.evaluate(() => window.testCalls);
  await page.evaluate(async () => {
    for (let i = 0; i < 30; i++) await window.__TAURI_INTERNALS__.invoke('plugin:event|emit', {event:'clips-changed'});
  });
  if (await page.evaluate(() => window.testCalls) !== hiddenCalls) throw new Error('Hidden refresh');
  await page.evaluate(() => window.__TAURI_INTERNALS__.invoke('plugin:event|emit',{event:'window-shown'}));
  await page.getByText('记录 1000',{exact:false}).waitFor();
  await page.locator('.list').evaluate(el => el.scrollTop=0);
  await page.getByText('记录 1\n第二行内容\n第三行内容…',{exact:true}).waitFor();

  return {burstRequests:burst-before, restoredScroll:restored, mountedRows:await page.locator('.item').count()};
}
