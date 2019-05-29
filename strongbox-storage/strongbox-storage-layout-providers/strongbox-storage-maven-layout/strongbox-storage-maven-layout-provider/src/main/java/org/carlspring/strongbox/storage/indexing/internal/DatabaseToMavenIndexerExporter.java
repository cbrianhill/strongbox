package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.services.ArtifactEntryService;
import org.carlspring.strongbox.storage.repository.Repository;

import javax.inject.Inject;
import java.nio.file.Paths;
import java.util.List;

import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.maven.index.ArtifactInfo;
import org.codehaus.plexus.util.StringUtils;
import org.springframework.stereotype.Component;

/**
 * @author Przemyslaw Fusik
 */
@Component
public class DatabaseToMavenIndexerExporter
{

    private static final int LIMIT = 1000;

    @Inject
    private CompositeArtifactEntryArtifactInfoIndexCreator compositeArtifactEntryArtifactInfoIndexCreator;

    @Inject
    private ArtifactEntryService artifactEntryService;

    public void export(final Repository repository)
    {

        int iteration = 0;

        for (; ; )
        {
            final int skip = iteration * LIMIT;
            iteration++;
            final List<ArtifactEntry> artifactEntries = artifactEntryService.findArtifactList(
                    repository.getStorage().getId(),
                    repository.getId(),
                    Collections.emptyMap(),
                    Collections.emptySet(), skip,
                    LIMIT,
                    "uuid", true);
            if (CollectionUtils.isEmpty(artifactEntries))
            {
                break;
            }

            final Document doc = new Document();

            for (final ArtifactEntry artifactEntry : artifactEntries)
            {
                if (!isIndexable(artifactEntry))
                {
                    continue;
                }

                final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();

                final ArtifactInfo artifactInfo =
                        new ArtifactInfo(artifactEntry.getRepositoryId(), coordinates.getGroupId(),
                                         coordinates.getArtifactId(),
                                         coordinates.getVersion(), coordinates.getClassifier(),
                                         coordinates.getExtension());

                // store extension if classifier is not empty
                if (!StringUtils.isEmpty(artifactInfo.getClassifier()))
                {
                    artifactInfo.setPackaging(coordinates.getExtension());
                }

                compositeArtifactEntryArtifactInfoIndexCreator.populateArtifactInfo(artifactInfo, artifactEntry);
            }
        }
    }

    /**
     * @see org.apache.maven.index.ArtifactContext#createDocument(org.apache.maven.index.context.IndexingContext)
     */
    public Document createDocument(final ArtifactInfo artifactInfo,
                                   final ArtifactEntry artifactEntry)
    {
        Document doc = new Document();

        // unique key
        doc.add(new Field(ArtifactInfo.UINFO, artifactInfo.getUinfo(), Field.Store.YES, Field.Index.NOT_ANALYZED));

        doc.add(new Field(ArtifactInfo.LAST_MODIFIED, //
                          Long.toString(System.currentTimeMillis()), Field.Store.YES, Field.Index.NO));

        compositeArtifactEntryArtifactInfoIndexCreator.populateArtifactInfo(artifactInfo, artifactEntry);

        // need a second pass in case index creators updated document attributes
        compositeArtifactEntryArtifactInfoIndexCreator.updateDocument(artifactInfo, doc);

        return doc;
    }

    /**
     * org.apache.maven.index.DefaultArtifactContextProducer#isIndexable(java.io.File)
     */
    protected boolean isIndexable(final ArtifactEntry artifactEntry)
    {
        final String filename = Paths.get(artifactEntry.getArtifactPath()).getFileName().toString();

        if (filename.equals("maven-metadata.xml")
            // || filename.endsWith( "-javadoc.jar" )
            // || filename.endsWith( "-javadocs.jar" )
            // || filename.endsWith( "-sources.jar" )
            || filename.endsWith(".properties")
            // || filename.endsWith( ".xml" ) // NEXUS-3029
            || filename.endsWith(".asc") || filename.endsWith(".md5") || filename.endsWith(".sha1"))
        {
            return false;
        }

        return true;
    }

}
