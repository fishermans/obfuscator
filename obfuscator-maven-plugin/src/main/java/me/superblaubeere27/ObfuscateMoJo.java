package me.superblaubeere27;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;

import lombok.Getter;
import me.superblaubeere27.jobf.JObf;

@Getter
@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class ObfuscateMoJo
    extends AbstractMojo {


  /**
   * Specifies the input jar file name of the application to be
   * processed.
   *
   */
  @Parameter(
      defaultValue = "${project.build.finalName}.jar",
      required = true)
  protected String injar;

  /**
   * Specifies the names of the output jars. If attach=true the value ignored and name constructed
   * base on classifier
   */
  @Parameter
  protected String outjar;

  /**
   * Directory containing the input and generated JAR.
   *
   */
  @Parameter(defaultValue = "${project.build.directory}", required = true)
  protected File directory;

  /**
   * Config File for obfuscation. If empty default config is used.
   * 
   */
  @Parameter
  protected File configFile;

  /**
   * JS ScriptFile for obfuscation. If empty default config is used.
   * 
   */
  @Parameter
  protected File scriptFile;

  /**
   * The Maven Project to use.
   */
  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject mavenProject;

  /**
   * The MavenSession instance to use.
   */
  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private MavenSession session;

  /**
   * The PlexusContainer instance to use.
   */
  private PlexusContainer plexusContainer;

  /**
   * List of dependency exclusions
   *
   */
  @Parameter
  private List<String> exclusions;

  /**
   * Specifies whether or not to attach the created artifact to the project
   *
   */
  @Parameter(defaultValue = "false")
  private boolean attach;

  /**
   * If true build will fail on any error
   *
   */
  @Parameter(defaultValue = "true")
  private boolean failOnError;

  /**
   * Specifies attach artifact type
   *
   */
  @Parameter(defaultValue = "jar")
  private String attachArtifactType;

  /**
   * Specifies attach artifact Classifier, Ignored if attach=false
   *
   */
  @Parameter(defaultValue = "")
  private String attachArtifactClassifier;

  /**
   * Set to true to include META-INF/maven/** maven descriptor
   *
   */
  @Parameter(defaultValue = "false")
  private boolean addMavenDescriptor;

  /** Field projectHelper */
  @Component
  private MavenProjectHelper projectHelper;

  /**
   * The Jar archiver.
   */
  @Component(role = Archiver.class, hint = "jar")
  private JarArchiver jarArchiver;

  @Parameter(defaultValue = "false")
  private boolean skip;

  @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
  private List<String> compilePath;

  /**
   * Set to false to exclude the attachArtifactClassifier from the Artifact final name. Default
   * value is true.
   *
   */
  @Parameter(defaultValue = "true")
  private boolean appendClassifier;


  @Override
  public void execute()
      throws MojoExecutionException {

    final Log log = getLog();

    if (isSkip()) {
      log.info("Skipping Obfuscator");
      return;
    }

    try {

      final File inJarFile = new File(directory, injar);
      if (!inJarFile.exists()) {
        if (!failOnError) {
          log.info("Bypass Obfuscator processing because \"injar\" dos not exist");
          return;
        }
      }

      if (!directory.exists()) {
        if (!directory.mkdirs()) {
          throw new MojoFailureException("Can't create " + directory);
        }
      }

      final File outJarFile;
      final boolean sameArtifact;

      if (attach) {
        outjar = FilenameUtils.getBaseName(injar);
        if (useArtifactClassifier()) {
          outjar += "-" + attachArtifactClassifier;
        }
        outjar += "." + attachArtifactType;
      }

      if ((outjar != null) && (!outjar.equals(injar))) {
        sameArtifact = false;
        outJarFile = new File(directory, outjar);
      } else {
        sameArtifact = true;
        outJarFile = getNewFileName(inJarFile, "_obfuscated_temp");
      }
      if (outJarFile.exists()) {
        if (!deleteFileOrDirectory(outJarFile)) {
          throw new MojoFailureException("Can't delete " + outJarFile);
        }
      }


      try {
        final boolean success = JObf.runEmbedded(
            inJarFile.getAbsolutePath(),
            outJarFile.getAbsolutePath(),
            getConfigFile(),
            getCompilePath(),
            readScriptFile());

        if (!success) {
          throw new MojoFailureException("Could not obfuscate inJar!");
        } else {
          final Pair<File, File> fileFilePair =
              renameOutputFile(inJarFile, outJarFile, sameArtifact);
          attachArtifacts(sameArtifact, fileFilePair);
        }
      } catch (final IOException | InterruptedException e) {
        throw new MojoExecutionException(e.getMessage(), e);
      }

    } catch (final MojoFailureException e) {
      final String message = "Error during obfuscation!";
      if (failOnError) {
        throw new MojoExecutionException(message, e);
      }
      log.error(message + ": " + e.getMessage());
    }
  }


  private void attachArtifacts( final boolean sameArtifact, final Pair<File, File> fileFilePair) {

    final Log log = getLog();
    if (!attach) {
      log.info(
          "Attaching artifacts is disabled. If outJar equals inJar final artifact will be the obfuscated file.");
    } else {
      final String classifier;
      if (useArtifactClassifier()) {
        classifier = attachArtifactClassifier.trim();
      } else {
        classifier = sameArtifact ? "obfuscated_base" : "obfuscated";
      }

      log.info(String.format("Attaching artifact %s:%s:%s:%s",
          mavenProject.getGroupId(),
          mavenProject.getArtifactId(),
          classifier,
          attachArtifactType));

      if (!sameArtifact) {
        projectHelper.attachArtifact(mavenProject, attachArtifactType, classifier,
            fileFilePair.getValue());
      } else {
        projectHelper.attachArtifact(mavenProject, attachArtifactType, classifier,
            fileFilePair.getKey());
      }

      /*
       * final String mainClassifier = useArtifactClassifier() ? attachArtifactClassifier :
       * null;
       * final File buildOutput = new File(mavenProject.getBuild().getDirectory());
       * if (attachMap) {
       * attachTextFile(new File(buildOutput, mappingFileName), mainClassifier, "map");
       * }
       * if (attachSeed) {
       * attachTextFile(new File(buildOutput, seedFileName), mainClassifier, "seed");
       * }
       */
    }
  }


  private String readScriptFile()
      throws IOException {

    if (getScriptFile() != null) {
      return new String(Files.readAllBytes(getScriptFile().toPath()), StandardCharsets.UTF_8);
    }
    return "";
  }


  private Pair<File, File> renameOutputFile(
      final File inJarFile,
      final File outJarFile,
      final boolean sameArtifact)
      throws MojoFailureException {

    final File finalOutFile;
    final File finalOrgFile;
    if (!sameArtifact) {
      finalOutFile = outJarFile;
      finalOrgFile = inJarFile;
    } else {
      if (!inJarFile.exists() || !outJarFile.exists()) {
        throw new MojoFailureException("injar or outjar missing! Abort!");
      }

      final File newInJarFile = getNewFileName(inJarFile, "_obfuscated_base");
      if (inJarFile.renameTo(newInJarFile)) {
        getLog().info("Renamed injar to " + newInJarFile);
        finalOrgFile = newInJarFile;
      } else {
        throw new MojoFailureException("Can't rename " + inJarFile);
      }


      if (!outJarFile.renameTo(inJarFile)) {
        throw new MojoFailureException("Can't rename " + outJarFile);
      } else {
        getLog().info("Renamed outjar to " + inJarFile);
        finalOutFile = inJarFile;
      }
    }
    return new ImmutablePair<>(finalOrgFile, finalOutFile);
  }


  private File getNewFileName(final File inJarFile, final String suffix) {

    final File newInJarFile;
    if (inJarFile.isDirectory()) {
      newInJarFile = new File(directory, FilenameUtils.getBaseName(injar) + suffix);
    } else {
      newInJarFile =
          new File(directory,
              FilenameUtils.getBaseName(injar) + suffix + "." + FilenameUtils.getExtension(injar));
    }
    return newInJarFile;
  }


  private boolean deleteFileOrDirectory(final File path) throws MojoFailureException {

    if (path.isDirectory()) {
      final File[] files = path.listFiles();
      if (null != files) {
        for (final File file : files) {
          if (file.isDirectory()) {
            if (!deleteFileOrDirectory(file)) {
              throw new MojoFailureException("Can't delete dir " + file);
            }
          } else {
            if (!file.delete()) {
              throw new MojoFailureException("Can't delete file " + file);
            }
          }
        }
      }
    }
    return path.delete();
  }


  private boolean useArtifactClassifier() {

    return appendClassifier && StringUtils.isNotBlank(attachArtifactClassifier);
  }
}
