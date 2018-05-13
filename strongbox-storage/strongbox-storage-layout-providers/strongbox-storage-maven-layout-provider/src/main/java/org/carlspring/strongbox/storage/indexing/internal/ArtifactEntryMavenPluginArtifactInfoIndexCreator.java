package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator.FLD_PLUGIN_GOALS;
import static org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator.FLD_PLUGIN_PREFIX;


/**
 * @author Przemyslaw Fusik
 * @see org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator
 */
@Component
@Order(3)
public class ArtifactEntryMavenPluginArtifactInfoIndexCreator
        implements ArtifactEntryArtifactInfoIndexCreator
{

    private static final String MAVEN_PLUGIN_PACKAGING = "maven-plugin";

    private static final Logger logger = LoggerFactory.getLogger(
            ArtifactEntryMavenPluginArtifactInfoIndexCreator.class);

    @Inject
    private RepositoryPathResolver repositoryPathResolver;

    /**
     * @see org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator#populateArtifactInfo(org.apache.maven.index.ArtifactContext)
     */
    @Override
    public void populateArtifactInfo(final ArtifactInfo artifactInfo,
                                     final ArtifactEntry artifactEntry)
    {

        // we need the file to perform these checks, and those may be only JARs
        if (MAVEN_PLUGIN_PACKAGING.equals(artifactInfo.getPackaging())
            && "jar".equals(artifactInfo.getFileExtension()))
        {

            final RepositoryPath artifactPath = repositoryPathResolver.resolve(artifactEntry.getStorageId(),
                                                                               artifactEntry.getRepositoryId(),
                                                                               artifactEntry.getArtifactPath());

            checkMavenPlugin(artifactInfo, artifactPath);
        }
    }

    /**
     * @see org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator#updateDocument(org.apache.maven.index.ArtifactInfo, org.apache.lucene.document.Document)
     */
    @Override
    public void updateDocument(final ArtifactInfo artifactInfo,
                               final Document doc)
    {
        if (artifactInfo.getPrefix() != null)
        {
            doc.add(FLD_PLUGIN_PREFIX.toField(artifactInfo.getPrefix()));
        }

        if (artifactInfo.getGoals() != null)
        {
            doc.add(FLD_PLUGIN_GOALS.toField(ArtifactInfo.lst2str(artifactInfo.getGoals())));
        }
    }

    /**
     * @see MavenPluginArtifactInfoIndexCreator#checkMavenPlugin(org.apache.maven.index.ArtifactInfo, java.io.File)
     */
    private void checkMavenPlugin(final ArtifactInfo artifactInfo,
                                  final Path artifactPath)
    {

        try (final FileSystem zipFileSystem = ArtifactEntryArtifactInfoIndexCreator.createZipFileSystem(artifactPath))
        {
            final Path root = zipFileSystem.getPath("/");

            final XmlPullParserException[] xmlPullParserException = new XmlPullParserException[1];

            //walk the zip file tree and copy files to the destination
            Files.walkFileTree(root, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile(final Path file,
                                                 final BasicFileAttributes attrs)
                        throws IOException
                {

                    if ("/META-INF/maven/plugin.xml".equals(file.toString()))
                    {
                        try (final InputStream inputStream = new BufferedInputStream(Files.newInputStream(file)))
                        {
                            try (final Reader reader = new InputStreamReader(inputStream))
                            {

                                final PlexusConfiguration plexusConfig = new XmlPlexusConfiguration(Xpp3DomBuilder.build(
                                        reader));

                                artifactInfo.setPrefix(plexusConfig.getChild("goalPrefix").getValue());

                                artifactInfo.setGoals(new ArrayList<>());

                                PlexusConfiguration[] mojoConfigs = plexusConfig.getChild("mojos").getChildren("mojo");

                                for (final PlexusConfiguration mojoConfig : mojoConfigs)
                                {
                                    artifactInfo.getGoals().add(mojoConfig.getChild("goal").getValue());
                                }
                            }
                            catch (final XmlPullParserException e)
                            {
                                xmlPullParserException[0] = e;
                            }
                        }
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (xmlPullParserException[0] != null)
            {
                throw xmlPullParserException[0];
            }
        }
        catch (IOException | XmlPullParserException e)
        {
            logger.warn(String.format("Failed to parse Maven artifact: %s due to exception:", artifactPath), e);
        }
    }
}
