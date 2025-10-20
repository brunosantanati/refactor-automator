package com.bruno.santana;

import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationRequest;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RefactorAutomator {
    private static final Logger logger = LoggerFactory.getLogger(RefactorAutomator.class);

    private final GitHub github;
    private final Path workDir;
    private final String oldGroupId;
    private final String oldArtifactId;
    private final String newVersion;
    private final String githubToken;
    private final String customRecipeName = "com.bruno.santana.UpgradeDependencyVersionExample";

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
        logger.info("Applying OpenRewrite recipe to update {} using maven-invoker", oldArtifactId);

        // 1. Create the rewrite.yml file
        writeRewriteYml(repoPath);

        // 2. Prepare the Maven Invoker Request
        InvocationRequest request = new DefaultInvocationRequest();

        // Set the base directory for the Maven execution (the cloned repo)
        request.setBaseDirectory(repoPath.toFile());

        // This requires the rewrite plugin to be configured in the target repo's POM
        String rewriteGoal = "rewrite:run";
        request.setGoals(Collections.singletonList(rewriteGoal));

        // Set the properties (active recipe and where to find the recipe file)
        request.setProperties(new java.util.Properties() {{
            // Tell OpenRewrite to look for recipes in the current directory's rewrite.yml
            setProperty("rewrite.configLocation", repoPath.resolve("rewrite.yml").toString());

            // Tell OpenRewrite which recipe to run (our custom wrapper)
            setProperty("rewrite.activeRecipes", customRecipeName);
        }});

        // 3. Execute the Invoker
        Invoker invoker = new DefaultInvoker();

        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) {
            mavenHome = System.getenv("M2_HOME");
        }
        if (mavenHome != null && !mavenHome.isEmpty()) {
            invoker.setMavenHome(new File(mavenHome));
            logger.info("Using Maven Home: {}", mavenHome);
        } else {
            // If Maven home isn't set, invoker relies on 'mvn' being in the PATH
            logger.warn("MAVEN_HOME/M2_HOME not set. Invoker relies on 'mvn' being in PATH.");
        }

        InvocationResult result = invoker.execute(request);

        // 4. Check the result
        if (result.getExitCode() != 0) {
            // Log the error and throw an exception to stop processing this repo
            logger.error("OpenRewrite execution failed. Exit code: {}", result.getExitCode());
            if (result.getExecutionException() != null) {
                throw new Exception("Maven execution failed", result.getExecutionException());
            } else {
                throw new Exception("OpenRewrite exited with code " + result.getExitCode());
            }
        } else {
            logger.info("OpenRewrite completed successfully");
        }
    }

    private void writeRewriteYml(Path repoPath) throws IOException {
        Path rewriteYmlPath = repoPath.resolve("rewrite.yml");
        logger.info("Creating custom recipe file: {}", rewriteYmlPath);

        String yamlContent = String.format(
                "---%n" +
                        "type: specs.openrewrite.org/v1beta/recipe%n" +
                        "name: %s%n" +
                        "displayName: Upgrade Maven dependency version example%n" +
                        "recipeList:%n" +
                        "  - org.openrewrite.maven.UpgradeDependencyVersion:%n" +
                        "      groupId: %s%n" +
                        "      artifactId: %s%n" +
                        "      newVersion: %s%n",
                customRecipeName,
                oldGroupId,
                oldArtifactId,
                newVersion
        );

        Files.write(rewriteYmlPath, yamlContent.getBytes(StandardCharsets.UTF_8));
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

        // 1. Stage files
        logger.info("Staging files");
        git.add().addFilepattern(".").call();

        // 2. Unstage the temporary rewrite.yml file (defensive cleanup)
        // If the file exists, this ensures it's not part of the commit.
        try {
            git.reset().addPath("rewrite.yml").call();
        } catch (Exception e) {
            // Ignore error if rewrite.yml doesn't exist or isn't staged
        }

        // 3. Check if there are any staged changes remaining to be committed
        org.eclipse.jgit.api.Status status = git.status().call();

        // Check if the index (staging area) contains any changes (added, changed)
        boolean hasStagedChanges = !status.getAdded().isEmpty() || !status.getChanged().isEmpty();

        if (hasStagedChanges) {
            // Commit
            String commitMessage = String.format("chore: upgrade %s to %s", artifactId, version);
            git.commit()
                    .setMessage(commitMessage)
                    .setAuthor("OpenRewrite Bot", "bot@example.com")
                    .call();
            logger.info("Changes committed with message: {}", commitMessage);
        } else {
            logger.warn("No pom.xml changes were staged for commit, skipping commit.");
        }

        git.close();
        repository.close();
    }

    private void pushBranch(Path repoPath, String branchName) throws IOException, GitAPIException {
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