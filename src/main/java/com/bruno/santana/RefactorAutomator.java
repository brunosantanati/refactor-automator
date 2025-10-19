package com.bruno.santana;

import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RefactorAutomator {
    private static final Logger logger = LoggerFactory.getLogger(RefactorAutomator.class);

    private final GitHub github;
    private final Path workDir;
    private final String oldGroupId;
    private final String oldArtifactId;
    private final String newVersion;
    private final String githubToken;

    public RefactorAutomator(String githubToken,
                             String oldGroupId, String oldArtifactId,
                             String newVersion, Path workDir) throws IOException {
        this.github = GitHub.connectUsingOAuth(githubToken);
        this.githubToken = githubToken;
        this.oldGroupId = oldGroupId;
        this.oldArtifactId = oldArtifactId;
        this.newVersion = newVersion;
        this.workDir = workDir;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: java RefactorAutomator <githubToken> " +
                    "<oldGroupId> <oldArtifactId> <newVersion> <repo1> <repo2> ...");
            System.exit(1);
        }

        String githubToken = args[0];
        String oldGroupId = args[1];
        String oldArtifactId = args[2];
        String newVersion = args[3];

        List<String> repos = Arrays.asList(Arrays.copyOfRange(args, 4, args.length));

        Path workDir = Files.createTempDirectory("openrewrite-bot-");
        logger.info("Working directory: {}", workDir);

        RefactorAutomator bot = new RefactorAutomator(
                githubToken, oldGroupId, oldArtifactId, newVersion, workDir
        );

        bot.processRepositories(repos);
    }

    private void processRepositories(List<String> repos) {
        for (String repo : repos) {
            try {
                logger.info("=== Processing: {} ===", repo);
                processRepository(repo);
            } catch (Exception e) {
                logger.error("Error processing {}: {}", repo, e.getMessage(), e);
            }
        }
    }

    private void processRepository(String repo) throws Exception {
        String[] parts = repo.split("/");
        String owner = parts[0];
        String repoName = parts[1];

        Path repoPath = workDir.resolve(repoName);

        try {
            // Clone the repository
            cloneRepository(owner, repoName, repoPath);

            // Create a feature branch
            String branchName = "openrewrite/update-" + oldArtifactId + "-" + System.currentTimeMillis();
            createAndCheckoutBranch(repoPath, branchName);

            // Apply OpenRewrite recipe
            applyOpenRewriteRecipe(repoPath);

            // Check if changes were made
            if (hasChanges(repoPath)) {
                // Commit changes
                commitChanges(repoPath, oldArtifactId, newVersion);

                // Push to remote
                pushBranch(repoPath, branchName);

                // Create pull request
                createPullRequest(owner, repoName, branchName, oldArtifactId, newVersion);

                logger.info("✓ PR created successfully for {}", repo);
            } else {
                logger.info("⊘ No changes detected in {}", repo);
            }
        } finally {
            // Cleanup
            if (Files.exists(repoPath)) {
                deleteDirectory(repoPath.toFile());
            }
        }
    }

    private void cloneRepository(String owner, String repoName, Path repoPath) throws GitAPIException, IOException {
        logger.info("Cloning: {}/{}", owner, repoName);
        String cloneUrl = "https://github.com/" + owner + "/" + repoName + ".git";

        UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider("x-access-token", githubToken);

        Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(repoPath.toFile())
                .setCredentialsProvider(credentials)
                .call()
                .close();

        logger.info("Repository cloned to: {}", repoPath);
    }

    private void createAndCheckoutBranch(Path repoPath, String branchName) throws IOException, GitAPIException {
        logger.info("Creating and checking out branch: {}", branchName);

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoPath.resolve(".git").toFile())
                .build();

        Git git = new Git(repository);

        git.checkout()
                .setCreateBranch(true)
                .setName(branchName)
                .call();

        git.close();
        repository.close();

        logger.info("Branch created and checked out: {}", branchName);
    }

    private void applyOpenRewriteRecipe(Path repoPath) throws Exception {
        //Better check this doc and try to fix this code: https://docs.openrewrite.org/recipes/maven/upgradedependencyversion

        logger.info("Applying OpenRewrite recipe to update {}", oldArtifactId);

        try {
            // 1. Get the full path to the Maven executable
            String mavenHome = System.getenv("MAVEN_HOME");
            if (mavenHome == null) {
                mavenHome = System.getenv("M2_HOME");
            }

            String mavenExecutable;
            if (mavenHome != null && !mavenHome.isEmpty()) {
                mavenExecutable = Paths.get(mavenHome, "bin", "mvn").toString();
                logger.info("Using Maven executable: {}", mavenExecutable);
            } else {
                logger.warn("MAVEN_HOME or M2_HOME not set. Falling back to 'mvn'.");
                mavenExecutable = "mvn";
            }

            String rewriteVersion = "5.41.0";
            String rewriteGoal = String.format(
                    "org.openrewrite.maven:rewrite-maven-plugin:%s:run",
                    rewriteVersion
            );

            // === THIS IS THE FIX ===
            // 2. Build the ENTIRE command as a single string,
            //    but wrap each -D property in quotes for the shell.
            String commandString = String.format(
                    "%s %s \"-Drewrite.activeRecipes=org.openrewrite.maven.UpgradeDependencyVersion\" \"-DgroupId=%s\" \"-DartifactId=%s\" \"-DnewVersion=%s\"",
                    mavenExecutable,
                    rewriteGoal,
                    oldGroupId,
                    oldArtifactId,
                    newVersion
            );
            // === END FIX ===

            logger.info("Running command via shell: {}", commandString);
            logger.info("  groupId: {}", oldGroupId);
            logger.info("  artifactId: {}", oldArtifactId);
            logger.info("  newVersion: {}", newVersion);

            // 3. Execute the command string using the system shell
            ProcessBuilder pb = new ProcessBuilder(
                    "/bin/sh",  // The shell
                    "-c",       // Argument to pass a command string
                    commandString // The command
            );

            pb.directory(repoPath.toFile()); // Run it in the cloned repo's directory
            pb.inheritIO(); // Show the output in our logs

            Process process = pb.start();
            int exitCode = process.waitFor(); // Wait for it to finish

            if (exitCode != 0) {
                logger.warn("OpenRewrite exited with code: {}", exitCode);
            } else {
                logger.info("OpenRewrite completed successfully");
            }

            logger.info("OpenRewrite recipe applied");

        } catch (Exception e) {
            logger.error("Error applying OpenRewrite recipe: {}", e.getMessage(), e);
            throw e;
        }
    }

    private boolean hasChanges(Path repoPath) throws IOException, GitAPIException {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoPath.resolve(".git").toFile())
                .build();

        Git git = new Git(repository);

        boolean hasChanges = !git.status().call().isClean();

        git.close();
        repository.close();

        return hasChanges;
    }

    private void commitChanges(Path repoPath, String artifactId, String version)
            throws IOException, GitAPIException {
        logger.info("Committing changes");

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoPath.resolve(".git").toFile())
                .build();

        Git git = new Git(repository);

        // Stage all changes
        git.add().addFilepattern(".").call();

        // Commit
        String commitMessage = String.format("chore: upgrade %s to %s", artifactId, version);
        git.commit()
                .setMessage(commitMessage)
                .setAuthor("OpenRewrite Bot", "bot@example.com")
                .call();

        git.close();
        repository.close();

        logger.info("Changes committed with message: {}", commitMessage);
    }

    private void pushBranch(Path repoPath, String branchName) throws IOException, GitAPIException {
        logger.info("Pushing branch: {}", branchName);

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoPath.resolve(".git").toFile())
                .build();

        Git git = new Git(repository);

        UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider("x-access-token", githubToken);

        git.push()
                .setCredentialsProvider(credentials)
                .call();

        git.close();
        repository.close();

        logger.info("Branch pushed successfully");
    }

    private void createPullRequest(String owner, String repoName, String branchName,
                                   String artifactId, String version) throws IOException {
        logger.info("Creating pull request");

        GHRepository ghRepository = github.getRepository(owner + "/" + repoName);

        String title = String.format("chore: upgrade %s to %s", artifactId, version);
        String body = String.format(
                "Automated dependency update via OpenRewrite\n\n" +
                        "Dependency: %s\n" +
                        "New Version: %s\n" +
                        "Created by: OpenRewrite Dependency Update Bot",
                artifactId, version
        );

        ghRepository.createPullRequest(title, branchName, "main", body);

        logger.info("Pull request created successfully");
    }

    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}