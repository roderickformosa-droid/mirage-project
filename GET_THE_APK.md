# Get the Mirage Field Tester APK with GitHub Actions

This project is configured so GitHub can build the Android APK for you; you do not need Android Studio or Gradle installed locally.

1. Create a new **private** GitHub repository.
2. Upload the **contents of this folder** to the repository root. Make sure `.github/workflows/build.yml` is included.
3. Open the repository's **Actions** tab.
4. Select **Build APK** in the left column.
5. Press **Run workflow**, then **Run workflow** again.
6. When the run finishes successfully, open it and download the artifact named **mirage_field_tester**. Inside it is `mirage_field_tester.apk`.
7. Transfer the APK to the Android phone and install it. Android may ask you to allow installs from the app/browser you used to open it.

## If the build fails
Open the failed **Build debug APK** step and copy the error text into ChatGPT. The workflow uses Java 17 and Gradle 8.7, matching Android Gradle Plugin 8.5.2.
