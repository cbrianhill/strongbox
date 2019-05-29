package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;
import org.carlspring.strongbox.services.ArtifactEntryService;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactAvailability;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_ARTIFACT_ID;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_ARTIFACT_ID_KW;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_CLASSIFIER;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_DESCRIPTION;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_EXTENSION;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_GROUP_ID;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_GROUP_ID_KW;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_INFO;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_NAME;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_PACKAGING;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_SHA1;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_VERSION;
import static org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator.FLD_VERSION_KW;

/**
 * @author Przemyslaw Fusik
 * @see org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator
 */
@Component
@Order(1)
public class ArtifactEntryMinimalArtifactInfoIndexCreator
        implements ArtifactEntryArtifactInfoIndexCreator
{

    private static final Logger logger = LoggerFactory.getLogger(ArtifactEntryMinimalArtifactInfoIndexCreator.class);

    private static final String JAVADOC = "javadoc";

    private static final String SOURCES = "sources";

    @Inject
    private RepositoryPathResolver repositoryPathResolver;

    @Inject
    private ArtifactEntryService artifactEntryService;


    /**
     * @see org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator#populateArtifactInfo(org.apache.maven.index.ArtifactContext)
     */
    @Override
    public void populateArtifactInfo(final ArtifactInfo artifactInfo,
                                     final ArtifactEntry artifactEntry)
    {

        artifactInfo.setLastModified(artifactEntry.getLastUpdated().getTime());

        artifactInfo.setSize(artifactEntry.getSizeInBytes());

        final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        if (coordinates == null)
        {
            return;
        }

        if (artifactInfo.getClassifier() != null)
        {
            artifactInfo.setSourcesExists(ArtifactAvailability.NOT_AVAILABLE);

            artifactInfo.setJavadocExists(ArtifactAvailability.NOT_AVAILABLE);
        }
        else
        {

            if (JAVADOC.equals(coordinates.getClassifier()) || javadocExists(artifactEntry, artifactEntryService))
            {
                artifactInfo.setJavadocExists(ArtifactAvailability.PRESENT);
            }
            else
            {
                artifactInfo.setJavadocExists(ArtifactAvailability.NOT_PRESENT);
            }

            if (SOURCES.equals(coordinates.getClassifier()) || sourcesExists(artifactEntry, artifactEntryService))
            {
                artifactInfo.setSourcesExists(ArtifactAvailability.PRESENT);
            }
            else
            {
                artifactInfo.setSourcesExists(ArtifactAvailability.NOT_PRESENT);
            }
        }

        final Model model = getPomModel(artifactEntry);

        if (model != null)
        {
            artifactInfo.setName(model.getName());

            artifactInfo.setDescription(model.getDescription());

            // for main artifacts (without classifier) only:
            if (artifactInfo.getClassifier() == null)
            {
                // only when this is not a classified artifact
                if (model.getPackaging() != null)
                {
                    // set the read value that is coming from POM
                    artifactInfo.setPackaging(model.getPackaging());
                }
                else
                {
                    // default it, since POM is present, is read, but does not contain explicit packaging
                    artifactInfo.setPackaging("jar");
                }
            }
        }

        final ArtifactEntry signature = getSignatureArtifactEntry(artifactEntry, artifactEntryService);
        if (signature != null)
        {
            artifactInfo.setSignatureExists(ArtifactAvailability.PRESENT);
            final RepositoryPath repositoryPath = repositoryPathResolver.resolve(signature.getStorageId(),
                                                                                 signature.getRepositoryId(),
                                                                                 signature.getArtifactPath());
            try
            {
                final String content = new String(Files.readAllBytes(repositoryPath), StandardCharsets.UTF_8);
                artifactInfo.setSha1(StringUtils.chomp(content).trim().split(" ")[0]);
            }
            catch (final IOException e)
            {
                logger.error("IOException during sha1 signature file reading.", e);
            }
        }
        else
        {
            artifactInfo.setSignatureExists(ArtifactAvailability.NOT_PRESENT);
        }
    }

    /**
     * @see MinimalArtifactInfoIndexCreator#updateDocument(org.apache.maven.index.ArtifactInfo, org.apache.lucene.document.Document)
     */
    public void updateDocument(final ArtifactInfo artifactInfo,
                               final Document doc)
    {
        final String info =
                new StringBuilder().append(ArtifactInfo.nvl(artifactInfo.getPackaging()))
                                   .append(ArtifactInfo.FS).append(Long.toString(artifactInfo.getLastModified()))
                                   .append(ArtifactInfo.FS).append(Long.toString(artifactInfo.getSize()))
                                   .append(ArtifactInfo.FS).append(artifactInfo.getSourcesExists().toString())
                                   .append(ArtifactInfo.FS).append(artifactInfo.getJavadocExists().toString())
                                   .append(ArtifactInfo.FS).append(artifactInfo.getSignatureExists().toString())
                                   .append(ArtifactInfo.FS).append(artifactInfo.getFileExtension()).toString();

        doc.add(FLD_INFO.toField(info));

        doc.add(FLD_GROUP_ID_KW.toField(artifactInfo.getGroupId()));
        doc.add(FLD_ARTIFACT_ID_KW.toField(artifactInfo.getArtifactId()));
        doc.add(FLD_VERSION_KW.toField(artifactInfo.getVersion()));

        // V3
        doc.add(FLD_GROUP_ID.toField(artifactInfo.getGroupId()));
        doc.add(FLD_ARTIFACT_ID.toField(artifactInfo.getArtifactId()));
        doc.add(FLD_VERSION.toField(artifactInfo.getVersion()));
        doc.add(FLD_EXTENSION.toField(artifactInfo.getFileExtension()));

        if (artifactInfo.getName() != null)
        {
            doc.add(FLD_NAME.toField(artifactInfo.getName()));
        }

        if (artifactInfo.getDescription() != null)
        {
            doc.add(FLD_DESCRIPTION.toField(artifactInfo.getDescription()));
        }

        if (artifactInfo.getPackaging() != null)
        {
            doc.add(FLD_PACKAGING.toField(artifactInfo.getPackaging()));
        }

        if (artifactInfo.getClassifier() != null)
        {
            doc.add(FLD_CLASSIFIER.toField(artifactInfo.getClassifier()));
        }

        if (artifactInfo.getSha1() != null)
        {
            doc.add(FLD_SHA1.toField(artifactInfo.getSha1()));
        }
    }

    /**
     * org.apache.maven.index.ArtifactContext#getPomModel()
     */
    public Model getPomModel(final ArtifactEntry artifactEntry)
    {
        final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        final String pomFileName = coordinates.getArtifactId() + "-" + coordinates.getVersion() + ".pom";

        final RepositoryPath artifactPath = repositoryPathResolver.resolve(artifactEntry.getStorageId(),
                                                                           artifactEntry.getRepositoryId(),
                                                                           artifactEntry.getArtifactPath());

        final RepositoryPath pomPath = artifactPath.getParent().resolve(pomFileName);

        final Model[] model = new Model[1];

        // First check for local pom file
        if (Files.exists(pomPath))
        {
            try (final InputStream inputStream = new BufferedInputStream(Files.newInputStream(pomPath)))
            {
                return new MavenXpp3Reader().read(inputStream, false);
            }
            catch (final IOException | XmlPullParserException e)
            {
                logger.warn(String.format("skip error reading pom: %s ", pomPath), e);
            }
        }
        // Otherwise, check for pom contained in maven generated artifact
        else if (Files.exists(artifactPath))
        {
            try (final FileSystem zipFileSystem = ArtifactEntryArtifactInfoIndexCreator.createZipFileSystem(
                    artifactPath))
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

                        if ("pom.xml".equals(file.getFileName().toString()) && file.toString().startsWith("/META-INF"))
                        {
                            try (final InputStream inputStream = new BufferedInputStream(Files.newInputStream(file)))
                            {
                                try
                                {
                                    model[0] = new MavenXpp3Reader().read(inputStream, false);
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
                logger.warn(String.format("skip error reading pom withing artifact: %s", artifactPath), e);
            }
        }

        return model[0];
    }

    private boolean javadocExists(final ArtifactEntry artifactEntry,
                                  final ArtifactEntryService artifactEntryService)
    {
        return classifierExists(artifactEntry, artifactEntryService, JAVADOC);
    }

    private ArtifactEntry getSignatureArtifactEntry(final ArtifactEntry artifactEntry,
                                                    final ArtifactEntryService artifactEntryService)
    {
        final Map<String, String> coordinates = artifactEntry.getArtifactCoordinates().getCoordinates();
        coordinates.put("extension", "sha1");
        final List<ArtifactEntry> artifactEntries = artifactEntryService.findArtifactList(artifactEntry.getStorageId(),
                                                                                          artifactEntry.getRepositoryId(),
                                                                                          coordinates, true);
        if (CollectionUtils.isEmpty(artifactEntries))
        {
            return null;
        }
        if (artifactEntries.size() > 1)
        {
            logger.warn(String.format("Received %d artifact entries when at most 1 was expected. Entries = [%s]",
                                      artifactEntries.size(), StringUtils.join(", ", artifactEntries)));
        }
        return artifactEntries.get(0);

    }

    private boolean sourcesExists(final ArtifactEntry artifactEntry,
                                  final ArtifactEntryService artifactEntryService)
    {
        return classifierExists(artifactEntry, artifactEntryService, SOURCES);
    }

    private boolean classifierExists(final ArtifactEntry artifactEntry,
                                     final ArtifactEntryService artifactEntryService,
                                     final String classifier)
    {
        final Map<String, String> coordinates = artifactEntry.getArtifactCoordinates().getCoordinates();
        coordinates.put("classifier", classifier);
        return artifactEntryService.countArtifacts(artifactEntry.getStorageId(), artifactEntry.getRepositoryId(),
                                                   coordinates, true) > 0;
    }
}
