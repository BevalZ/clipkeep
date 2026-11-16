// Run with playwright-cli run-code --filename ui/checks/reliability.js against Vite.
async (page) => {
  await page.route('**/src/main.ts*', async route => {
    const response = await route.fetch();
    const source = await response.text();
    await route.fulfill({response, body: `
      import { mockIPC, mockWindows } from '/node_modules/@tauri-apps/api/mocks.js';
      mockWindows('main');
      window.checkState = {
        settings: {keepOnBlur:false, rememberPosition:false, theme:'light', maxStorageMb:500,
          hotkeyEnabled:true, hotkey:'ctrl+shift+v', captureImages:true, windowX:null, windowY:null},
        calls:[], writes:[], active:0, maximum:0, failNext:false
      };
      mockIPC(async (cmd, args) => {
        const state = window.checkState;
        state.calls.push(cmd);
        if (cmd === 'plugin:window|is_visible') return true;
        if (cmd === 'list_clips') return [1,2].map(id => ({id,kind:'text',preview:'item '+id,
          size:6,truncated:false,favorite:false,pinned:false,updatedAt:Date.now(),source:null,groupId:null,thumb:null}));
        if (cmd === 'list_groups') return [];
        if (cmd === 'get_settings') return {settings:structuredClone(state.settings),hotkeys:[],dataDir:'mock'};
        if (cmd === 'update_settings') {
          if (!args.patch || args.settings) throw new Error('Expected a settings patch');
          state.writes.push(structuredClone(args.patch));
          state.maximum = Math.max(state.maximum, ++state.active);
          await new Promise(resolve => setTimeout(resolve, 300));
          state.active--;
          if (state.failNext) { state.failNext=false; throw new Error('Simulated disk failure'); }
          Object.assign(state.settings, args.patch);
          return structuredClone(state.settings);
        }
        if (cmd === 'get_lan_state') return {receiveEnabled:false,peers:[],localCodes:[]};
        if (cmd === 'get_storage_usage') return {ordinaryBytes:0,protectedBytes:0,databaseBytes:0};
        return false;
      }, {shouldMockEvents:true});
      ${source}`});
  });
  await page.setViewportSize({width:400,height:600});
  await page.reload();
  await page.locator('.item').first().waitFor();
  const ime = await page.evaluate(() => {
    const input = document.querySelector('.search input');
    input.focus();
    const before = window.checkState.calls.length;
    const selected = document.querySelector('.item.selected .text').textContent;
    for (const key of ['Enter','ArrowDown','ArrowUp','Escape']) {
      for (const flags of [{isComposing:true},{keyCode:229}]) {
        const event = new KeyboardEvent('keydown',{key,bubbles:true,cancelable:true,...flags});
        input.dispatchEvent(event);
        if (event.defaultPrevented) throw new Error('IME key was intercepted: '+key);
      }
    }
    if (document.querySelector('.item.selected .text').textContent !== selected) throw new Error('IME changed selection');
    if (window.checkState.calls.slice(before).some(cmd => ['paste_clip','copy_clip','hide_window'].includes(cmd)))
      throw new Error('IME triggered an application action');
    return true;
  });
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(() => document.querySelector('.item.selected .text')?.textContent === 'item 2');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.checkState.calls.includes('paste_clip'));

  await page.getByRole('button',{name:'设置',exact:true}).click();
  const pinned = page.locator('label.row').filter({hasText:'固定窗口（失焦不隐藏）'}).locator('input');
  const remembered = page.locator('label.row').filter({hasText:'记住窗口位置'}).locator('input');
  await pinned.click();
  await remembered.click();
  await page.waitForFunction(() => window.checkState.writes.length===2 && window.checkState.active===0);
  if (!await pinned.isChecked() || !await remembered.isChecked()) throw new Error('Independent setting was lost');
  await pinned.click();
  await pinned.click();
  await page.waitForFunction(() => window.checkState.writes.length===4 && window.checkState.active===0);
  if (!await pinned.isChecked()) throw new Error('Rapid toggle was lost');

  await page.evaluate(() => window.checkState.failNext=true);
  await page.locator('label.row').filter({hasText:'记录图片'}).locator('input').click();
  await page.getByRole('button',{name:'深色',exact:true}).click();
  await page.waitForFunction(() => window.checkState.writes.length===6 && window.checkState.active===0);
  if (!await page.locator('label.row').filter({hasText:'记录图片'}).locator('input').isChecked())
    throw new Error('Failed setting was not restored');
  if (await page.getByRole('button',{name:'深色',exact:true}).getAttribute('aria-pressed') !== 'true')
    throw new Error('A failed write blocked later changes');

  // A component may disappear while a write is queued. Reopening must await that write.
  await remembered.click();
  await page.getByRole('button',{name:'返回 (Esc)'}).click();
  await page.getByRole('button',{name:'设置',exact:true}).click();
  await remembered.waitFor();
  if (await remembered.isChecked()) throw new Error('Reopened settings read stale state');
  const state = await page.evaluate(() => window.checkState);
  if (state.maximum !== 1) throw new Error('Settings writes ran concurrently');
  return {ime,settingsWrites:state.writes.length,maximumConcurrentWrites:state.maximum};
}
