package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.domain.ArtifactEntry;

import javax.inject.Inject;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactInfo;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * @author Przemyslaw Fusik
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CompositeArtifactEntryArtifactInfoIndexCreator
        implements ArtifactEntryArtifactInfoIndexCreator
{

    @Inject
    private List<ArtifactEntryArtifactInfoIndexCreator> creators;


    @Override
    public void populateArtifactInfo(final ArtifactInfo artifactInfo,
                                     final ArtifactEntry artifactEntry)
    {
        creators.stream().forEach(creator -> creator.populateArtifactInfo(artifactInfo, artifactEntry));
    }

    @Override
    public void updateDocument(final ArtifactInfo artifactInfo,
                               final Document doc)
    {
        creators.stream().forEach(creator -> creator.updateDocument(artifactInfo, doc));
    }
}
