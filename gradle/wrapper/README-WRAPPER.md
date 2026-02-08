# Gradle Wrapper

This project uses the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) so that everyone uses the same Gradle version without installing it manually.

## If `./gradlew` or `gradlew.bat` fails (e.g. "Could not find or load main class GradleWrapperMain")

The wrapper needs `gradle-wrapper.jar` in this folder. If it's missing (e.g. project was set up without it), do one of the following:

### Option 1: Generate with Gradle (if you have Gradle installed)

```bash
gradle wrapper --gradle-version=8.5
```

Install Gradle first from https://gradle.org/install/ or via SDKMAN: `sdk install gradle 8.5`

### Option 2: Download the JAR

- **Windows (PowerShell):**
  ```powershell
  Invoke-WebRequest -Uri "https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle/wrapper/gradle-wrapper.jar"
  ```

- **Linux / macOS (curl):**
  ```bash
  curl -L -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar
  ```

Then run from the project root:

- **Windows:** `gradlew.bat build`
- **Linux / macOS:** `./gradlew build`
