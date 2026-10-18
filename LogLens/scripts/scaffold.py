"""Create the initial SDD module build graph; no generated business behavior."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    'loglens-api': [],
    'loglens-bootstrap': [],
    'loglens-config': ['loglens-api', 'yaml'],
    'loglens-processors': ['loglens-api'],
    'loglens-storage': ['loglens-api', 'loglens-config', 'json', 'sqlite'],
    'loglens-ui': [],
    'loglens-web': ['loglens-storage', 'loglens-ui'],
    'loglens-runtime': ['loglens-processors', 'loglens-storage', 'loglens-web', 'disruptor'],
    'loglens-adapter-logback': ['loglens-runtime', 'loglens-bootstrap'],
    'loglens-agent': ['loglens-adapter-logback', 'bytebuddy'],
    'loglens-viewer': ['loglens-web'],
    'examples/logback-demo': ['logback'],
    'examples/custom-plugin': ['loglens-api:provided'],
    'examples/custom-chain-plugin': ['loglens-api:provided'],
    'examples/springboot-demo': ['spring'],
    'loglens-integration-tests': ['loglens-agent', 'loglens-viewer', 'logback'],
}
EXTERNAL = {
    'yaml': ('org.yaml', 'snakeyaml', '${snakeyaml.version}'),
    'json': ('com.fasterxml.jackson.core', 'jackson-databind', '${jackson.version}'),
    'sqlite': ('org.xerial', 'sqlite-jdbc', '${sqlite.version}'),
    'disruptor': ('com.lmax', 'disruptor', '${disruptor.version}'),
    'bytebuddy': ('net.bytebuddy', 'byte-buddy', '${bytebuddy.version}'),
    'logback': ('ch.qos.logback', 'logback-classic', '${logback.version}'),
    'spring': ('org.springframework.boot', 'spring-boot-starter', '${springboot.version}'),
}

def dep(group, artifact, version, scope=None):
    return f'<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>' + (f'<scope>{scope}</scope>' if scope else '') + '</dependency>'

root = '''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion><groupId>io.loglens</groupId><artifactId>loglens-parent</artifactId><version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
<name>LogLens</name><description>Local Java log workbench</description>
<properties>
<maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<junit.version>5.11.4</junit.version><snakeyaml.version>2.3</snakeyaml.version><jackson.version>2.18.3</jackson.version>
<sqlite.version>3.49.1.0</sqlite.version><disruptor.version>4.0.0</disruptor.version><bytebuddy.version>1.15.11</bytebuddy.version>
<logback.version>1.5.18</logback.version><springboot.version>3.4.4</springboot.version>
<loglens.build.directory>${maven.multiModuleProjectDirectory}/.build</loglens.build.directory>
</properties>
<modules>MODULES</modules>
<dependencyManagement><dependencies>DEPENDENCIES</dependencies></dependencyManagement>
<build><pluginManagement><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version><configuration>
<argLine>-Djava.io.tmpdir=${loglens.build.directory}/tmp -Duser.home=${loglens.build.directory}/user -Dfile.encoding=UTF-8</argLine>
<systemPropertyVariables><loglens.build.directory>${loglens.build.directory}</loglens.build.directory><org.sqlite.tmpdir>${loglens.build.directory}/native</org.sqlite.tmpdir></systemPropertyVariables>
</configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version><configuration><argLine>-Djava.io.tmpdir=${loglens.build.directory}/tmp -Duser.home=${loglens.build.directory}/user</argLine></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-clean-plugin</artifactId><version>3.4.0</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version></plugin>
</plugins></pluginManagement></build>
</project>'''
managed = [dep('org.junit.jupiter', 'junit-jupiter', '${junit.version}')]
(ROOT / 'pom.xml').write_text(root.replace('MODULES', ''.join(f'<module>{m}</module>' for m in MODULES)).replace('DEPENDENCIES', ''.join(managed)), encoding='utf-8')
for module, dependencies in MODULES.items():
    directory = ROOT / module
    directory.mkdir(parents=True, exist_ok=True)
    items = []
    for d in dependencies:
        if d in EXTERNAL:
            items.append(dep(*EXTERNAL[d]))
        else:
            artifact, _, scope = d.partition(':')
            items.append(dep('io.loglens', artifact, '${project.version}', scope or None))
    items.append('<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>')
    relative = '../../pom.xml' if '/' in module else '../pom.xml'
    pom = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion><parent><groupId>io.loglens</groupId><artifactId>loglens-parent</artifactId><version>0.1.0-SNAPSHOT</version><relativePath>{relative}</relativePath></parent>
<artifactId>{module.split('/')[-1]}</artifactId><dependencies>{''.join(items)}</dependencies>
</project>'''
    (directory / 'pom.xml').write_text(pom, encoding='utf-8')
