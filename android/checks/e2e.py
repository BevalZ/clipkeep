import subprocess as sp,time,xml.etree.ElementTree as ET,re,socket,base64,os,threading,json,struct
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
import argparse,zlib
parser=argparse.ArgumentParser(description="Destructive emulator-only E2E for a debug installation; requires Python cryptography.")
parser.add_argument('--reset-test-installation', action='store_true', required=True)
parser.parse_args()
adb=[os.environ.get('ADB', 'adb'),'-s',os.environ.get('CLIPKEEP_TEST_DEVICE','emulator-5554')]
assert adb[-1].startswith('emulator-'), 'Use a disposable emulator'
chunk=lambda kind,data:struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
test_png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff'))+chunk(b'IEND',b'')
def run(*args):return sp.check_output(adb+list(args),stderr=sp.DEVNULL)
def nodes():
 run('shell','uiautomator','dump','/sdcard/clipkeep-ui.xml')
 return list(ET.fromstring(run('shell','cat','/sdcard/clipkeep-ui.xml')).iter('node'))
def point(n):
 v=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));return str((v[0]+v[2])//2),str((v[1]+v[3])//2)
def click(text):
 for n in nodes():
  if n.attrib.get('text')==text:
   run('shell','input','tap',*point(n));time.sleep(.3);return
 raise Exception('missing '+text+' visible='+str([n.get('text') for n in nodes() if n.get('text')]))
def fill(index,text):
 fields=[n for n in nodes() if n.get('class')=='android.widget.EditText'];run('shell','input','tap',*point(fields[index]));run('shell','input','text',text)
def exact(s,n):
 out=b''
 while len(out)<n:
  part=s.recv(n-len(out))
  if not part:raise Exception('EOF')
  out+=part
 return out
key=bytes(range(32));cipher=ChaCha20Poly1305(key);received=[];errors=[];stop=False
server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind(('0.0.0.0',45788));server.listen(4);server.settimeout(.3)
udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('0.0.0.0',45788));udp.settimeout(.3)
def discover():
 while not stop:
  try:
   data,addr=udp.recvfrom(128)
   if data.startswith(b'CKD1? '):udp.sendto(json.dumps({'protocol':'CKD1','nonce':data[6:].decode(),'name':'Desktop-test','port':45788,'images':True,'pair':True}).encode(),addr)
  except socket.timeout:pass
  except OSError:return
def receive():
 while not stop:
  try:s,addr=server.accept()
  except socket.timeout:continue
  except OSError:return
  try:
   with s:
    s.settimeout(10);h=exact(s,20);body=exact(s,int.from_bytes(h[16:20],'big'))
    aad={b'CKP1':b'clipkeep-lan-pair-v1',b'CKI1':b'clipkeep-lan-image-v1',b'CKL1':b'clipkeep-lan-text-v1',b'CKH1':b'clipkeep-lan-probe-v1'}[h[:4]]
    plain=cipher.decrypt(h[4:16],body,aad);received.append((h[:4],plain))
    nonce=os.urandom(12)
    if h[:4]==b'CKH1':
     reply=cipher.encrypt(nonce,plain,b'clipkeep-lan-probe-reply-v1');s.sendall(b'CKR1'+nonce+len(reply).to_bytes(4,'big')+reply)
    else:s.sendall(nonce+cipher.encrypt(nonce,b'OK',b'clipkeep-lan-ack-v1'))
  except Exception as e:errors.append(str(e))
threading.Thread(target=discover,daemon=True).start();threading.Thread(target=receive,daemon=True).start()
try:
 run('shell','run-as','com.clipkeep.android','true');run('shell','am','force-stop','com.clipkeep.android');run('shell','pm','clear','com.clipkeep.android');run('shell','pm','grant','com.clipkeep.android','android.permission.POST_NOTIFICATIONS');run('shell','am','start','-n','com.clipkeep.android/.MainActivity');time.sleep(1)
 click('设备');click('扫描响应设备');fill(0,'10.0.2.2');click('扫描');time.sleep(4)
 click('Desktop-test · 10.0.2.2');fill(1,'cklan1://10.0.2.2:45788/'+base64.urlsafe_b64encode(key).decode().rstrip('='));click('添加');time.sleep(2)
 assert received and received[0][0]==b'CKP1',received
 phonekey=base64.urlsafe_b64decode(json.loads(received[0][1])['key']+'=');pcipher=ChaCha20Poly1305(phonekey)
 print('Scan -> select -> authenticated mutual pairing passed',flush=True)
 click('设备');click('管理已配对设备');click('Desktop-test');click('测试连接');time.sleep(1)
 assert any(m==b'CKH1' for m,p in received),received
 assert not any('clipkeep-lan-probe' in n.get('text','') for n in nodes())
 print('Android connection-test UI and authenticated challenge reply passed',flush=True)
 click('设备');click('复制与同步设置');click('收到后复制到系统剪贴板');click('完成')
 run('forward','tcp:45789','tcp:45788');run('shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
 def send(magic,plain):
  nonce=os.urandom(12);aad={b'CKI1':b'clipkeep-lan-image-v1',b'CKL1':b'clipkeep-lan-text-v1'}[magic];body=pcipher.encrypt(nonce,plain,aad)
  with socket.create_connection(('127.0.0.1',45789),timeout=5) as sock:
   sock.sendall(magic+nonce+len(body).to_bytes(4,'big')+body);ack=exact(sock,30);assert pcipher.decrypt(ack[:12],ack[12:],b'clipkeep-lan-ack-v1')==b'OK'
 send(b'CKL1','后台文本 中文 😀'.encode());png=test_png;send(b'CKI1',png)
 service=run('shell','dumpsys','activity','services','com.clipkeep.android').decode();assert 'isForeground=true' in service
 print('Background text + image receive and authenticated ACK passed',flush=True)
 # Keep the screen locked, then test a network transition and a system process kill.
 run('shell','input','keyevent','KEYCODE_SLEEP');time.sleep(.5)
 send(b'CKL1','锁屏接收'.encode())
 run('shell','svc','wifi','disable');run('shell','svc','data','disable');time.sleep(1)
 run('shell','svc','wifi','enable');run('shell','svc','data','enable');time.sleep(2)
 send(b'CKL1','网络切换后接收'.encode())
 oldpid=run('shell','pidof','com.clipkeep.android').decode().strip()
 run('shell','run-as','com.clipkeep.android','kill','-9',oldpid)
 until=time.monotonic()+45;restored=False
 while time.monotonic()<until:
  time.sleep(1)
  try:
   newpid=run('shell','pidof','com.clipkeep.android').decode().strip()
   if newpid and newpid!=oldpid:
    send(b'CKL1','系统重建后接收'.encode());restored=True;break
  except Exception:pass
 assert restored,'Sticky foreground receiver did not recover'
 print('Locked-screen receive, Wi-Fi/data transition and sticky process recovery passed',flush=True)
 run('shell','input','keyevent','KEYCODE_WAKEUP');run('shell','wm','dismiss-keyguard')

 run('shell','am','start','-n','com.clipkeep.android/.MainActivity');time.sleep(1)
 assert any(n.get('text')=='[图片]' for n in nodes());assert any('系统重建后接收' in n.get('text','') for n in nodes())
 # Copy received image URI back into history to exercise provider URI reads and de-duplication.
 click('粘贴保存');time.sleep(1)
 for n in nodes():
  if n.get('text')=='[图片]':
   x,y=point(n);run('shell','input','swipe',x,y,x,y,'800');break
 click('发送到设备');click('Desktop-test');time.sleep(2)
 assert any(m==b'CKI1' and p==png for m,p in received),[(m,len(p)) for m,p in received]
 print('Android image clipboard URI and reverse encrypted image transfer passed',flush=True)
 click('设备');click('关闭后台接收');time.sleep(1)
 try:send(b'CKL1',b'should fail');raise AssertionError('still receiving')
 except (ConnectionError,OSError,Exception) as e:
  if isinstance(e,AssertionError):raise
 # A persisted explicit stop must survive a new app process.
 run('shell','am','force-stop','com.clipkeep.android');run('shell','am','start','-n','com.clipkeep.android/.MainActivity');time.sleep(1)
 assert 'isForeground=true' not in run('shell','dumpsys','activity','services','com.clipkeep.android').decode()
 assert not errors,errors
 print('Explicit stop closes receiver; E2E passed',flush=True)
finally:
 stop=True;server.close();udp.close()
 run('shell','svc','wifi','enable');run('shell','svc','data','enable');run('shell','input','keyevent','KEYCODE_WAKEUP')
