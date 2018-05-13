package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.creator.JarFileContentsIndexCreator;
import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static org.apache.maven.index.creator.JarFileContentsIndexCreator.FLD_CLASSNAMES;
import static org.apache.maven.index.creator.JarFileContentsIndexCreator.FLD_CLASSNAMES_KW;

/**
 * @author Przemyslaw Fusik
 * @see org.apache.maven.index.creator.JarFileContentsIndexCreator
 */
@Component
@Order(2)
public class ArtifactEntryJarFileContentsIndexCreator
        implements ArtifactEntryArtifactInfoIndexCreator
{

    private static final Logger logger = LoggerFactory.getLogger(ArtifactEntryJarFileContentsIndexCreator.class);

    @Inject
    private RepositoryPathResolver repositoryPathResolver;

    /**
     * @see org.apache.maven.index.creator.JarFileContentsIndexCreator#populateArtifactInfo(org.apache.maven.index.ArtifactContext)
     */
    @Override
    public void populateArtifactInfo(final ArtifactInfo artifactInfo,
                                     final ArtifactEntry artifactEntry)
    {
        final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        if (coordinates == null)
        {
            return;
        }

        if ("jar".equals(coordinates.getExtension()) || "war".equals(coordinates.getExtension()))
        {
            try
            {
                updateArtifactInfo(artifactInfo, artifactEntry);
            }
            catch (final IOException e)
            {
                logger.error("IOException during jar or war file content reading.", e);
            }
        }
    }

    /**
     * @see org.apache.maven.index.creator.JarFileContentsIndexCreator#updateDocument(org.apache.maven.index.ArtifactInfo, org.apache.lucene.document.Document)
     */
    @Override
    public void updateDocument(final ArtifactInfo artifactInfo,
                               final Document doc)
    {
        if (artifactInfo.getClassNames() != null)
        {
            doc.add(FLD_CLASSNAMES_KW.toField(artifactInfo.getClassNames()));
            doc.add(FLD_CLASSNAMES.toField(artifactInfo.getClassNames()));
        }
    }

    /**
     * @see JarFileContentsIndexCreator#updateArtifactInfo(org.apache.maven.index.ArtifactInfo, java.io.File)
     */
    private void updateArtifactInfo(final ArtifactInfo artifactInfo,
                                    final ArtifactEntry artifactEntry)
            throws IOException
    {
        final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        if ("jar".equals(coordinates.getExtension()))
        {
            updateArtifactInfo(artifactInfo, artifactEntry, null);
        }
        else if ("war".equals(coordinates.getExtension()))
        {
            updateArtifactInfo(artifactInfo, artifactEntry, "WEB-INF/classes/");
        }
    }

    /**
     * @see org.apache.maven.index.creator.JarFileContentsIndexCreator#updateArtifactInfo(org.apache.maven.index.ArtifactInfo, java.io.File, java.lang.String)
     */
    private void updateArtifactInfo(final ArtifactInfo artifactInfo,
                                    final ArtifactEntry artifactEntry,
                                    final String strippedPrefix)
            throws IOException
    {
        final RepositoryPath artifactPath = repositoryPathResolver.resolve(artifactEntry.getStorageId(),
                                                                           artifactEntry.getRepositoryId(),
                                                                           artifactEntry.getArtifactPath());

        try (final ZipInputStream inputStream = new ZipInputStream(Files.newInputStream(artifactPath)))
        {

            final StringBuilder sb = new StringBuilder();

            ZipEntry entry;

            while ((entry = inputStream.getNextEntry()) != null)
            {
                final String name = entry.getName();
                if (name.endsWith(".class"))
                {
                    final int i = name.indexOf("$");

                    if (i == -1)
                    {
                        if (name.charAt(0) != '/')
                        {
                            sb.append('/');
                        }

                        if (StringUtils.isBlank(strippedPrefix))
                        {
                            // class name without ".class"
                            sb.append(name.substring(0, name.length() - 6)).append('\n');
                        }
                        else if (name.startsWith(strippedPrefix)
                                 && (name.length() > (strippedPrefix.length() + 6)))
                        {
                            // class name without ".class" and stripped prefix
                            sb.append(name.substring(strippedPrefix.length(), name.length() - 6)).append('\n');
                        }
                    }
                }
            }

            final String fieldValue = sb.toString().trim();

            if (fieldValue.length() != 0)
            {
                artifactInfo.setClassNames(fieldValue);
            }
            else
            {
                artifactInfo.setClassNames(null);
            }
        }
    }
}
