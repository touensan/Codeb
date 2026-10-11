// Executed by official Codex command/exec under the selected Android UID.
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const {execFileSync}=require('node:child_process');
// ponytail: Android SELinux denies app-data hard links; use coreutils' no-clobber rename.
function moveNew(source,destination) {execFileSync('/data/data/com.termux/files/usr/bin/mv',['-T','-n','--',source,destination]);if(fs.existsSync(source))throw Error('Destination already exists');}
const q=JSON.parse(process.argv[1]);
const valid=p=>typeof p==='string' && p.startsWith('/') && !p.includes('\0');
if(!valid(q.path)) throw Error('Invalid absolute path');
const entry=p=>{const s=fs.lstatSync(p);return {name:path.basename(p),path:p,directory:s.isDirectory(),link:s.isSymbolicLink(),size:s.size,modified:s.mtimeMs,mode:(s.mode&4095).toString(8)};};
let result={};
if(q.action==='list') { const names=fs.readdirSync(q.path); if(names.length>5000)throw Error('Directory exceeds 5000 entries; choose a subdirectory');result={entries:names.map(n=>{try{return entry(path.join(q.path,n))}catch{return null}}).filter(Boolean)}; }
else if(q.action==='info') result=entry(q.path);
else if(q.action==='mkdir') fs.mkdirSync(q.path,{mode:448});
else if(q.action==='delete') { const s=fs.lstatSync(q.path); if(s.isDirectory())fs.rmdirSync(q.path);else fs.unlinkSync(q.path); }
else if(q.action==='rename') {
  if(!valid(q.destination)||path.dirname(q.destination)!==path.dirname(q.path))throw Error('Invalid destination');
  if(fs.lstatSync(q.path).isSymbolicLink())throw Error('Symbolic links cannot be renamed here');
  moveNew(q.path,q.destination);
} else if(q.action==='commit') {
  if(!valid(q.staging)||path.dirname(q.staging)!==path.dirname(q.path)||!/^\.codeb-[a-f0-9-]+\.tmp$/.test(path.basename(q.staging)))throw Error('Invalid staging file');
  const staged=fs.lstatSync(q.staging); if(!staged.isFile()||staged.size>(q.revision===null?18:1)*1048576)throw Error('New files are limited to 18 MB; text editing to 1 MB');
  const exists=fs.existsSync(q.path);
  if(q.revision===null) { if(exists)throw Error('File already exists');moveNew(q.staging,q.path); }
  else {
    if(!exists)throw Error('File was removed; reopen before saving');
    const s=fs.lstatSync(q.path);
    if(!s.isFile()||s.isSymbolicLink()||s.size>1048576)throw Error('Not an editable regular file');
    const digest=crypto.createHash('sha256').update(fs.readFileSync(q.path)).digest('hex');
    if(digest!==q.revision)throw Error('File changed elsewhere; reopen before saving');
    fs.chmodSync(q.staging,s.mode&4095);if(process.getuid()===0)fs.chownSync(q.staging,s.uid,s.gid);
    const fd=fs.openSync(q.staging,'r');fs.fsyncSync(fd);fs.closeSync(fd);
    fs.renameSync(q.staging,q.path);
  }
} else throw Error('Unknown file operation');
process.stdout.write(JSON.stringify(result));
