"""Updater, colour preview, delayed responses and tray readiness with Tauri mock IPC."""
import os
from playwright.sync_api import expect, sync_playwright

MOCK = r'''
import {mockIPC, mockWindows} from '/node_modules/@tauri-apps/api/mocks.js';
import {emit} from '/node_modules/@tauri-apps/api/event.js';
mockWindows(location.search.includes('tray') ? 'tray_menu' : 'main');
window.qa = {calls:[], settings:{theme:'light',keepOnBlur:false,captureImages:true,
  checkUpdateOnStart:true,pasteImageAsFile:false,maxStorageMb:500,hotkeyEnabled:true,hotkey:'win+v'},
  update:{kind:'idle'}, holdGroups:false, holdUpdate:true, groupReplies:[], updateReplies:[]};
mockIPC(async (cmd,args={}) => {
  qa.calls.push({cmd,args});
  if(cmd==='plugin:window|is_visible') return true;
  if(cmd==='plugin:app|version') return '0.4.2';
  if(cmd==='list_groups') {
    if(qa.holdGroups) return new Promise(resolve=>qa.groupReplies.push(resolve));
    return [];
  }
  if(cmd==='list_clips') return [{id:1,kind:'text',preview:'#1234',color:'#1234',size:5,
    truncated:false,favorite:false,pinned:false,updatedAt:Date.now(),source:null,groupId:null,thumb:null}];
  if(cmd==='get_settings') return {settings:structuredClone(qa.settings),hotkeys:[],dataDir:'mock'};
  if(cmd==='update_settings') { Object.assign(qa.settings,args.patch); return structuredClone(qa.settings); }
  if(cmd==='get_update_state') {
    if(qa.holdUpdate) return new Promise(resolve=>qa.updateReplies.push(resolve));
    return structuredClone(qa.update);
  }
  if(cmd==='check_update') { qa.update={kind:'available',version:'0.4.3',size:123,portable:false}; await emit('update-state',qa.update); }
  if(cmd==='download_update') { qa.update={kind:'downloading',version:'0.4.3',received:50,total:100}; await emit('update-state',qa.update); }
  if(cmd==='cancel_update') { qa.update={kind:'available',version:'0.4.3',size:123,portable:false}; await emit('update-state',qa.update); }
  if(cmd==='get_lan_state') return {receiveEnabled:false,peers:[],localCodes:[]};
  if(cmd==='get_storage_usage') return {ordinaryBytes:0,protectedBytes:0,databaseBytes:0};
  return false;
},{shouldMockEvents:true});
'''

with sync_playwright() as p:
    browser = p.chromium.launch(
        executable_path=os.environ.get('CLIPKEEP_QA_BROWSER_EXE'), headless=True)
    page = browser.new_page(viewport={'width': 400, 'height': 600})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    def intercept(route):
        response = route.fetch()
        route.fulfill(response=response, body=MOCK + '\n' + response.text())

    page.route('**/src/main.ts*', intercept)
    page.goto('http://127.0.0.1:1420/')
    expect(page.locator('.hex-preview')).to_have_text('#1234')
    assert page.locator('.item').evaluate('el=>el.getBoundingClientRect().height') == 112

    def event(name, payload=None):
        page.evaluate('async ([name,payload])=>(await import("/node_modules/@tauri-apps/api/event.js")).emit(name,payload)', [name, payload])

    # A delayed initial fetch must not overwrite a newer event.
    page.wait_for_function('qa.updateReplies.length===1')
    event('update-state', {'kind': 'available', 'version': '0.4.3', 'size': 123, 'portable': False})
    page.evaluate('qa.updateReplies.shift()({kind:"idle"}); qa.holdUpdate=false')
    expect(page.locator('.settings-btn .update-dot')).to_be_visible()

    page.get_by_role('button', name='设置', exact=True).click()
    page.get_by_role('button', name='检查更新', exact=True).click()
    page.get_by_role('button', name='下载安装包', exact=True).click()
    expect(page.get_by_role('progressbar', name='下载进度')).to_have_attribute('aria-valuenow', '50')
    page.get_by_role('button', name='取消', exact=True).click()
    expect(page.get_by_role('button', name='下载安装包', exact=True)).to_be_visible()
    event('update-state', {'kind': 'ready', 'version': '0.4.3'})
    assert page.evaluate('qa.calls.filter(c=>c.cmd==="install_update").length') == 0
    page.get_by_role('button', name='立即安装', exact=True).click()
    assert page.evaluate('qa.calls.filter(c=>c.cmd==="install_update").length') == 1
    page.locator('label.row').filter(has_text='图片粘贴时附带文件名').locator('input').click()
    page.wait_for_function('qa.settings.pasteImageAsFile')
    assert page.evaluate('qa.calls.filter(c=>c.cmd==="update_settings").at(-1).args.patch') == {'pasteImageAsFile': True}

    # Group/status replies from a hidden session must not repopulate its view.
    page.get_by_role('button', name='返回 (Esc)').click()
    page.evaluate('qa.holdGroups=true')
    event('window-shown')
    page.wait_for_function('qa.groupReplies.length===1')
    event('window-hidden')
    page.evaluate('qa.groupReplies.shift()([{id:99,name:"stale",position:0}]);qa.holdGroups=false')
    expect(page.locator('.item')).to_have_count(0)
    event('window-shown')
    expect(page.locator('.hex-preview')).to_be_visible()

    page.goto('http://127.0.0.1:1420/?tray=1')
    page.wait_for_function('qa.calls.some(c=>c.cmd==="tray_menu_ready")')
    expect(page.get_by_role('button', name='打开主页面')).to_be_visible()
    calls = page.evaluate('qa.calls.map(c=>c.cmd)')
    assert calls.index('get_settings') < calls.index('tray_menu_ready')
    assert not errors, errors
    browser.close()
    print('PASS: colour row, update event ordering/download/cancel/install, settings patch, hidden replies, tray readiness')
