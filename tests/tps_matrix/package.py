#!/usr/bin/env python3
"""Offline reproducible packaging of tested project classes and local JDBC dependencies."""
import argparse,hashlib,json,shutil,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def build(out):
 private=out/'.private';backup=private/'package-backups';backup.mkdir(mode=0o700,exist_ok=True);backup.chmod(0o700)
 for name,source in [('root-before-tps-fixes.jar',ROOT/'DBChaos-0.0.1.jar'),('target-before-tps-fixes.jar',ROOT/'target/DBChaos-0.0.1.jar')]:
  target=backup/name
  if not target.exists():shutil.copy2(source,target);target.chmod(0o600)
 classes=out/'final-package-classes';classes.mkdir(exist_ok=True)
 r=subprocess.run(['javac','--release','8','-encoding','UTF-8','-cp',str(ROOT/'lib/*'),'-d',str(classes),*map(str,sorted((ROOT/'src').rglob('*.java')))],capture_output=True,text=True,check=True)
 (out/'final-package-build.log').write_text(r.stdout+r.stderr)
 entries={};services={}
 def merge(archive,preserve_manifest=False):
  with zipfile.ZipFile(archive) as z:
   for info in z.infolist():
    name=info.filename;upper=name.upper()
    if upper=='META-INF/MANIFEST.MF' and not preserve_manifest:continue
    if upper=='META-INF/INDEX.LIST' or upper.startswith('META-INF/') and upper.endswith(('.SF','.RSA','.DSA','.EC')):continue
    data=z.read(name)
    if name.startswith('META-INF/services/') and not name.endswith('/'):
     providers=services.setdefault(name,[])
     for line in data.decode('utf-8').splitlines():
      value=line.split('#',1)[0].strip()
      if value and value not in providers:providers.append(value)
    else:entries[name]=data
 merge(backup/'root-before-tps-fixes.jar',preserve_manifest=True)
 dependencies=sorted((ROOT/'lib').glob('*.jar'))
 for jar in dependencies:merge(jar)
 for name,providers in services.items():entries[name]=('\n'.join(providers)+'\n').encode()
 for file in classes.rglob('*.class'):entries[file.relative_to(classes).as_posix()]=file.read_bytes()
 staged=private/'DBChaos-updated.jar'
 with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED) as z:
  for name,data in entries.items():z.writestr(name,data)
 checks={}
 with zipfile.ZipFile(staged) as z:
  for name in ['chaos/Main.class','chaos/inject/MaxConnectionInject.class','chaos/monitor/ResilienceAnalysis.class']:
   checks[name]=z.read(name)==(classes/name).read_bytes()
  for jar,name in [('opengauss-jdbc-7.0.0-RC2.jar','org/opengauss/jdbc/PgConnection.class'),('postgresql-42.7.2.jar','org/postgresql/Driver.class'),('mysql-connector-j-8.0.33.jar','com/mysql/cj/jdbc/Driver.class')]:
   with zipfile.ZipFile(ROOT/'lib'/jar) as lib:checks[name]=z.read(name)==lib.read(name)
 assert all(checks.values()),checks
 smoke=private/'package-smoke';smoke.mkdir(exist_ok=True)
 source=smoke/'DriverClasspathSmoke.java';source.write_text('''import java.sql.*; public class DriverClasspathSmoke { public static void main(String[] args) throws Exception { String[][] drivers={{"org.opengauss.Driver","jdbc:opengauss://127.0.0.1:1/test"},{"org.postgresql.Driver","jdbc:postgresql://127.0.0.1:1/test"},{"com.mysql.cj.jdbc.Driver","jdbc:mysql://127.0.0.1:1/test"}}; for(String[] d:drivers) { Class.forName(d[0]); System.out.println(DriverManager.getDriver(d[1]).getClass().getName()); } } }''')
 subprocess.run(['javac','--release','8','-cp',str(staged),'-d',str(smoke),str(source)],capture_output=True,text=True,check=True)
 dr=subprocess.run(['java','-cp',str(staged)+':'+str(smoke),'DriverClasspathSmoke'],capture_output=True,text=True,timeout=20,check=True)
 help_result=subprocess.run(['java','-jar',str(staged),'--help'],capture_output=True,text=True,timeout=20,check=True)
 (out/'final-package-help.log').write_text(help_result.stdout+help_result.stderr)
 (out/'final-package-driver-smoke.log').write_text(dr.stdout+dr.stderr)
 for target in [ROOT/'DBChaos-0.0.1.jar',ROOT/'target/DBChaos-0.0.1.jar']:shutil.copy2(staged,target)
 result=dict(method='Offline local-dependency assembly; current --release 8 project classes, all repository lib JARs, merged service providers; original application resources retained and original JARs backed up privately; no Maven clean',checks=checks,help_exit_code=help_result.returncode,driver_load_exit_code=dr.returncode,root_jar_sha256=sha(ROOT/'DBChaos-0.0.1.jar'),target_jar_sha256=sha(ROOT/'target/DBChaos-0.0.1.jar'),source_sha256={str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'src').rglob('*.java')},dependency_sha256={p.name:sha(p) for p in dependencies},runtime_measurement_classes='injector-classes-abort-v2 kept unchanged throughout measurements; latest Pr boundary fix is applied transparently in derived-resilience.json when needed')
 (out/'final-package-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
 print('Package verified:',all(checks.values()),'help:',help_result.returncode,'all three JDBC drivers loaded:',dr.returncode==0,'root/target identical:',result['root_jar_sha256']==result['target_jar_sha256'])
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('out',type=Path);args=parser.parse_args();build(args.out.resolve())
