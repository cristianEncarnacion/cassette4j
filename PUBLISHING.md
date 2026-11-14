# Cómo publicar cassette4j

## 1. Subir a GitHub

```bash
cd cassette4j
git init -b main
git add .
git commit -m "cassette4j 0.1.0"
gh repo create cassette4j --public --source . --push
```

El workflow de `.github/workflows/ci.yml` corre `mvn verify` en Linux y Windows con Java 17 y 21 en cada push.

## 2. Probar en tu máquina

```bash
mvn verify
```

Todos los tests corren sin internet: usan un servidor LLM falso local y cassettes guardados en el repo.

## 3. Publicar en Maven Central (una sola vez)

1. Entra a https://central.sonatype.com con tu cuenta de GitHub. El namespace `io.github.cristianencarnacion` se verifica solo al entrar con GitHub. Si Central te muestra el namespace con mayúsculas (`io.github.cristianEncarnacion`), cambia el `groupId` de los 4 `pom.xml` para que coincida exactamente.
2. En **Account → Generate User Token** genera un token y ponlo en `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

3. Crea una llave GPG y publícala:

   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys TU_KEY_ID
   ```

4. Publica:

   ```bash
   mvn -P release deploy
   ```

En unos 30 minutos aparece en https://central.sonatype.com/artifact/io.github.cristianencarnacion/cassette4j-junit5.

## 4. Nueva versión

Cambia `0.1.0` por la nueva versión en los 4 `pom.xml` (o `mvn versions:set -DnewVersion=0.2.0`), haz commit, crea un tag `v0.2.0` y repite el paso 3.4.
