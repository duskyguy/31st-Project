/*
 * Copyright 2019 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.igormaznitsa.mvngolang;

import static com.igormaznitsa.meta.common.utils.Assertions.assertNotNull;


import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.mvngolang.utils.GoMod;
import com.igormaznitsa.mvngolang.utils.IOUtils;
import com.igormaznitsa.mvngolang.utils.MavenUtils;
import com.igormaznitsa.mvngolang.utils.ProxySettings;
import com.igormaznitsa.mvngolang.utils.Tuple;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.zeroturnaround.zip.ZipUtil;

public abstract class AbstractGoDependencyAwareMojo extends AbstractGolangMojo {

  public static final String GO_MOD_FILE_NAME_BAK = ".#go.mod.mvn.orig";
  public static final String DELETE_GO_SUM_FLAG_FILE = ".#go.mod.mvn.delete.sum";

  /**
   * Internal variable to keep GOPATH part containing folders of unpacked
   * mvn-golang dependencies.
   *
   * @since 2.3.0
   */
  private String extraGoPathSectionInOsFormat = "";

  /**
   * Find artifacts generated by Mvn-Golang among scope dependencies, unpack
   * them and add unpacked folders into GOPATH during execution.
   *
   * @since 2.3.0
   */
  @Parameter(name = "scanDependencies", defaultValue = "true")
  private boolean scanDependencies = true;

  /**
   * Include test dependencies into scanning process activated if
   * {@code scanDependencies=true}
   *
   * @since 2.3.0
   */
  @Parameter(name = "includeTestDependencies", defaultValue = "true")
  private boolean includeTestDependencies = true;

  /**
   * Path to the folder where resolved mvn-golang dependency artifacts will be
   * temporary unpacked and those paths will be added into GOPATH, activated if
   * {@code scanDependencies=true}
   *
   * @since 2.3.0
   */
  @Parameter(name = "dependencyTempFolder", defaultValue = "${project.build.directory}${file.separator}.__deps__")
  private String dependencyTempFolder;

  /**
   * Flag to turn on session synchronization to prevent parallel processing of
   * modules in module mode if session is parallel one. Can be defined through property 'mvn.golang.sync.session.if.modules'
   *
   * @see #isModuleMode()
   * @since 2.3.3
   */
  @Parameter(name = "syncSessionIfModules", defaultValue = "true")
  private boolean syncSessionIfModules;

  public boolean isSyncSessionIfModules() {
    return Boolean.parseBoolean(findMvnProperty("mvn.golang.sync.session.if.modules",
        Boolean.toString(this.syncSessionIfModules)));
  }

  public void setSyncSessionIfModules(final boolean value) {
    this.syncSessionIfModules = value;
  }

  @Nonnull
  public String getDependencyTempFolder() {
    return this.dependencyTempFolder;
  }

  public void setDependencyTempFolder(@Nonnull final String path) {
    this.dependencyTempFolder = assertNotNull(path);
  }

  public boolean isScanDependencies() {
    return this.scanDependencies;
  }

  public void setScanDependencies(final boolean flag) {
    this.scanDependencies = flag;
  }

  public boolean isIncludeTestDependencies() {
    return this.includeTestDependencies;
  }

  public void setIncludeTestDependencies(final boolean value) {
    this.includeTestDependencies = value;
  }

  @Nonnull
  private String makeRelativePathToFolder(@Nonnull final File goModFile,
                                          @Nonnull final File folder) {
    return goModFile.toPath().relativize(folder.toPath()).toString();
  }

  @Nonnull
  @MustNotContainNull
  private List<Tuple<Artifact, Tuple<GoMod, File>>> findModsInProject() throws IOException {
    final File sourceFolder = this.getSources(false);
    if (sourceFolder.isDirectory()) {
      return findGoModsAndParse(Collections
          .singletonList(Tuple.of(this.getProject().getArtifact(), sourceFolder)));
    } else {
      return Collections.emptyList();
    }
  }

  private void preprocessModules(
      @Nonnull @MustNotContainNull final List<Tuple<Artifact, File>> unpackedDependencyFolders)
      throws MojoExecutionException {
    try {
      final List<Tuple<Artifact, Tuple<GoMod, File>>> lst =
          preprocessModuleFilesInDependencies(unpackedDependencyFolders);
      final List<Tuple<GoMod, File>> dependencyGoMods = listRightPart(lst);

      final List<Tuple<Artifact, Tuple<GoMod, File>>> projectGoMods = findModsInProject();

      for (final Tuple<Artifact, Tuple<GoMod, File>> f : projectGoMods) {
        final File goModFile = f.right().right();
        final File workingFolder = goModFile.getParentFile();
        final File sumFile = new File(workingFolder, GO_SUM_FILE_NAME);
        final File goModFileBak = new File(workingFolder, GO_MOD_FILE_NAME_BAK);
        final File deleteSumFileFlag = new File(workingFolder, DELETE_GO_SUM_FLAG_FILE);

        if (goModFileBak.isFile()) {
          if (goModFile.isFile() && !goModFile.delete()) {
            throw new IOException("Can't delete go.mod file: " + goModFile);
          }
          if (deleteSumFileFlag.isFile() && sumFile.isFile() && !sumFile.delete()) {
            throw new IOException("Can't delete file " + sumFile);
          }
          FileUtils.copyFile(goModFileBak, goModFile);
        } else {
          if (goModFile.isFile()) {
            FileUtils.copyFile(goModFile, goModFileBak);
          }
          if (!sumFile.isFile() && !deleteSumFileFlag.isFile() &&
              !deleteSumFileFlag.createNewFile()) {
            throw new IOException("Can't create file " + deleteSumFileFlag);
          }
        }

        if (goModFile.isFile()) {
          final GoMod parsed =
              GoMod.from(FileUtils.readFileToString(goModFile, StandardCharsets.UTF_8));
          if (replaceLinksToModules(Tuple.of(parsed, goModFile), dependencyGoMods)) {
            FileUtils.write(goModFile, parsed.toString(), StandardCharsets.UTF_8);
          }
          if (sumFile.isFile() && !deleteSumFileFlag.isFile() &&
              !deleteSumFileFlag.createNewFile()) {
            throw new IOException("Can't create file " + deleteSumFileFlag);
          }
        }
      }
    } catch (IOException ex) {
      throw new MojoExecutionException("Can't process a go.mod file", ex);
    }
  }

  @Nonnull
  @MustNotContainNull
  private List<Tuple<Artifact, Tuple<GoMod, File>>> findGoModsAndParse(
      @Nonnull @MustNotContainNull final List<Tuple<Artifact, File>> unpackedFolders)
      throws IOException {
    final List<Tuple<Artifact, Tuple<GoMod, File>>> result = new ArrayList<>();

    for (final Tuple<Artifact, File> tuple : unpackedFolders) {
      for (final File f : FileUtils
          .listFiles(tuple.right(), FileFilterUtils.nameFileFilter("go.mod"),
              TrueFileFilter.INSTANCE)) {
        final GoMod model = GoMod.from(FileUtils.readFileToString(f, StandardCharsets.UTF_8));
        result.add(Tuple.of(tuple.left(), Tuple.of(model, f)));
      }
    }

    return result;
  }

  private boolean replaceLinksToModules(@Nonnull final Tuple<GoMod, File> source,
                                        @Nonnull @MustNotContainNull
                                        final List<Tuple<GoMod, File>> targets) throws IOException {
    boolean changed = false;
    for (final Tuple<GoMod, File> j : targets) {
      if (!source.equals(j)) {
        final GoMod thatParsedGoMod = j.left();
        final File thatGoModFile = j.right();

        if (source.left().hasRequireFor(thatParsedGoMod.getModule(), null) &&
            !source.left().hasReplaceFor(thatParsedGoMod.getModule(), null)) {

          final String relativePath = makeRelativePathToFolder(source.right().getParentFile(),
              thatGoModFile.getParentFile());
          source.left().addItem(
              new GoMod.GoReplace(new GoMod.ModuleInfo(thatParsedGoMod.getModule()),
                  new GoMod.ModuleInfo(relativePath)));
          changed = true;
        }
      }
    }
    return changed;
  }

  @Nonnull
  @MustNotContainNull
  private List<Tuple<GoMod, File>> listRightPart(
      @Nonnull @MustNotContainNull final List<Tuple<Artifact, Tuple<GoMod, File>>> list) {
    final List<Tuple<GoMod, File>> parsed = new ArrayList<>();
    for (final Tuple<Artifact, Tuple<GoMod, File>> i : list) {
      parsed.add(i.right());
    }
    return parsed;
  }

  private int generateCrossLinksBetweenArtifactGoMods(
      @Nonnull @MustNotContainNull final List<Tuple<Artifact, Tuple<GoMod, File>>> unpackedFolders)
      throws IOException {
    int changes = 0;

    final List<Tuple<GoMod, File>> parsed = listRightPart(unpackedFolders);

    for (final Tuple<GoMod, File> i : parsed) {
      if (replaceLinksToModules(i, parsed)) {
        changes++;
        FileUtils.write(i.right(), i.left().toString(), StandardCharsets.UTF_8);
      }
    }
    return changes;
  }

  @Nonnull
  @MustNotContainNull
  private List<Tuple<Artifact, Tuple<GoMod, File>>> preprocessModuleFilesInDependencies(
      @Nonnull @MustNotContainNull final List<Tuple<Artifact, File>> unpackedFolders)
      throws IOException {
    getLog().debug("Finding go.mod descriptors in unpacked artifacts");
    final List<Tuple<Artifact, Tuple<GoMod, File>>> foundAndParsedGoMods =
        findGoModsAndParse(unpackedFolders);
    getLog().debug(String.format("Found %d go.mod descriptors", foundAndParsedGoMods.size()));
    final int changedGoModCounter = generateCrossLinksBetweenArtifactGoMods(foundAndParsedGoMods);
    getLog().debug(
        String.format("Changed %d go.mod descriptors in unpacked artifacts", changedGoModCounter));

    return foundAndParsedGoMods;
  }

  @Override
  public final void doInit() throws MojoFailureException, MojoExecutionException {
    super.doInit();

    if (this.isModuleMode()) {
      try {
        final File src = this.getSources(false);
        this.restoreGoModFromBackupAndRemoveBackup(src);
      } catch (IOException ex) {
        throw new MojoExecutionException(
            "Error during restoring of detected go.mod backup in source folder", ex);
      }
    }

    if (this.isScanDependencies()) {
      getLog().info("Scanning maven dependencies");
      final List<Tuple<Artifact, File>> foundArtifacts;

      try {
        foundArtifacts = MavenUtils.scanForMvnGoArtifacts(
            this.getProject(),
            this.isIncludeTestDependencies(),
            this,
            this.getSession(),
            this.getExecution(),
            this.getArtifactResolver(),
            this.getRemoteRepositories());
      } catch (ArtifactResolverException ex) {
        throw new MojoFailureException("Can't resolve artifact", ex);
      }

      if (foundArtifacts.isEmpty()) {
        getLog().debug("Mvn golang dependencies are not found");
        this.preprocessModules(Collections.emptyList());
        this.extraGoPathSectionInOsFormat = "";
      } else {
        getLog().debug("Found mvn-golang artifacts: " + foundArtifacts);
        final File dependencyTempTargetFolder = new File(this.getDependencyTempFolder());
        getLog().debug("Dependencies will be unpacked into folder: " + dependencyTempTargetFolder);
        final List<Tuple<Artifact, File>> unpackedFolders =
            unpackArtifactsIntoFolder(foundArtifacts, dependencyTempTargetFolder);

        if (this.isModuleMode()) {
          this.getLog().info("Module mode is activated");
          this.preprocessModules(unpackedFolders);
          this.getLog().info("Dependencies are not added into GOPATH because module mode is on");
        } else {
          final List<File> unpackedFolderList = new ArrayList<>();
          for (final Tuple<Artifact, File> f : unpackedFolders) {
            unpackedFolderList.add(f.right());
          }

          final String preparedExtraPartForGoPath =
              IOUtils.makeOsFilePathWithoutDuplications(unpackedFolderList.toArray(new File[0]));
          getLog().debug("Prepared dependency path for GOPATH: " + preparedExtraPartForGoPath);
          this.extraGoPathSectionInOsFormat = preparedExtraPartForGoPath;
        }
      }
    } else {
      getLog().info("Maven dependency scanning is off");
    }
  }

  private void restoreGoModFromBackupAndRemoveBackup(@Nonnull final File folder)
      throws IOException {
    final Collection<File> backupFiles = FileUtils
        .listFiles(folder, FileFilterUtils.nameFileFilter(GO_MOD_FILE_NAME_BAK),
            TrueFileFilter.INSTANCE);

    this.getLog().debug(String
        .format("Restoring go.mod from backup in %s, detected %d files", folder,
            backupFiles.size()));

    for (final File backup : backupFiles) {
      final File workingFolder = backup.getParentFile();
      final File restored = new File(workingFolder, GO_MOD_FILE_NAME);
      final File goSumFile = new File(workingFolder, GO_SUM_FILE_NAME);
      final File deleteGoSumFileFlag = new File(workingFolder, DELETE_GO_SUM_FLAG_FILE);

      if (restored.isFile() && !restored.delete()) {
        throw new IOException("Can't delete file during backup restore: " + restored);
      }
      if (!backup.renameTo(restored)) {
        throw new IOException("Can't rename backup: " + backup + " -> " + restored);
      }

      if (deleteGoSumFileFlag.isFile()) {
        if (!deleteGoSumFileFlag.delete()) {
          throw new IOException("Can't delete file " + deleteGoSumFileFlag);
        }
        if (goSumFile.isFile() && !goSumFile.delete()) {
          throw new IOException("Can't delete file " + goSumFile);
        }
      }
    }
  }

  @Override
  public void afterExecution(@Nullable final ProxySettings proxySettings, final boolean error)
      throws MojoFailureException, MojoExecutionException {
    try {
      if (this.isModuleMode()) {
        final File srcFolder = this.getSources(false);
        if (srcFolder.isDirectory()) {
          if (this.isRestoreGoMod()) {
            this.getLog().debug("Restoring go.mod from backup in source folder: " + srcFolder);
            this.restoreGoModFromBackupAndRemoveBackup(srcFolder);
          } else {
            this.getLog().debug("Restoring of go.mod from backup is disabled by project property");
          }
        }
      }
    } catch (IOException ex) {
      throw new MojoExecutionException("Error during restore go.mod from backup", ex);
    } finally {
      super.afterExecution(proxySettings, error);
    }
  }

  @Override
  protected boolean doesNeedSessionLock() {
    return this.getSession().isParallel() && this.isModuleMode() && this.isSyncSessionIfModules();
  }

  protected boolean isRestoreGoMod() {
    return Boolean.parseBoolean(MavenUtils
        .findProperty(this.getSession(), this.getProject(), "mvn.golang.restore.go.mod", "true"));
  }

  @Nonnull
  @MustNotContainNull
  private List<Tuple<Artifact, File>> unpackArtifactsIntoFolder(@Nonnull
                                                                @MustNotContainNull
                                                                final List<Tuple<Artifact, File>> zippedArtifacts,
                                                                @Nonnull final File targetFolder)
      throws MojoExecutionException {
    final List<Tuple<Artifact, File>> resultFolders = new ArrayList<>();

    if (!targetFolder.isDirectory() && !targetFolder.mkdirs()) {
      throw new MojoExecutionException(
          "Can't create folder to unpack dependencies: " + targetFolder);
    }

    for (final Tuple<Artifact, File> zipFile : zippedArtifacts) {
      final File outDir =
          new File(targetFolder, FilenameUtils.getBaseName(zipFile.right().getName()));

      final boolean doUnpackArch;
      if (outDir.isDirectory()) {
        this.getLog().debug("Unpacked dependency folder already exists: " + outDir);
        if (Boolean.parseBoolean(MavenUtils
            .findProperty(this.getSession(), this.getProject(), "mvn.golang.force.clean.dependency",
                "false"))) {
          this.getLog().debug("Forcing dependency folder delete: " + outDir);
          try {
            FileUtils.deleteDirectory(outDir);
          } catch (IOException ex) {
            throw new MojoExecutionException("Can't delete dependency folder: " + outDir, ex);
          }
          doUnpackArch = true;
        } else {
          getLog().debug("Ignoring dependency unpack because folder exists: " + outDir);
          doUnpackArch = false;
        }
      } else {
        doUnpackArch = true;
      }

      if (doUnpackArch) {
        if (ZipUtil
            .containsEntry(zipFile.right(), GolangMvnInstallMojo.MVNGOLANG_BUILD_FOLDERS_FILE)) {
          final File srcTargetFolder = new File(outDir, "src");
          try {
            unzipSrcFoldersContent(zipFile.right(), srcTargetFolder);
          } catch (Exception ex) {
            throw new MojoExecutionException(
                "Can't unpack source folders from dependency archive '" +
                    zipFile.right().getName() + "' into folder '" + srcTargetFolder + '\'', ex);
          }
        } else {
          try {
            getLog().debug("Unpack dependency archive: " + zipFile);
            ZipUtil.unpack(zipFile.right(), outDir, StandardCharsets.UTF_8);
          } catch (Exception ex) {
            throw new MojoExecutionException(
                "Can't unpack dependency archive '" + zipFile.right().getName() +
                    "' into folder '" + targetFolder + '\'', ex);
          }
        }
      }

      resultFolders.add(Tuple.of(zipFile.left(), outDir));
    }
    return resultFolders;
  }

  private boolean unzipSrcFoldersContent(@Nonnull final File artifactZip,
                                         @Nonnull final File targetFolder) {
    final byte[] buildFolderListFile =
        ZipUtil.unpackEntry(artifactZip, GolangMvnInstallMojo.MVNGOLANG_BUILD_FOLDERS_FILE);
    if (buildFolderListFile == null) {
      return false;
    } else {
      final List<String> folderList = new ArrayList<>();
      for (final String folder : new String(buildFolderListFile, StandardCharsets.UTF_8)
          .split("\\n")) {
        final String trimmed = folder.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        folderList.add(folder + '/');
      }

      for (final String folder : folderList) {
        ZipUtil.unpack(artifactZip, targetFolder, (@Nonnull final String name) -> {
          if (name.startsWith(folder)) {
            return name.substring(folder.length());
          }
          return null;
        });
      }
      return true;
    }
  }

  @Nonnull
  @Override
  protected final String getSpecialPartOfGoPath() {
    return this.extraGoPathSectionInOsFormat;
  }

}
