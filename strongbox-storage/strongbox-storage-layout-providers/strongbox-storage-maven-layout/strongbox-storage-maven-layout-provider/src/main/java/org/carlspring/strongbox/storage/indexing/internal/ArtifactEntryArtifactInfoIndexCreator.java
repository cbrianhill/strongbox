package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.domain.ArtifactEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;

import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactInfo;

/**
 * @author Przemyslaw Fusik
 */
public interface ArtifactEntryArtifactInfoIndexCreator
{

    static FileSystem createZipFileSystem(final Path artifactPath)
            throws IOException
    {
        final URI uri = URI.create("jar:file:" + artifactPath.toUri().getPath());
        return FileSystems.newFileSystem(uri, new HashMap<String, String>());
    }

    void populateArtifactInfo(final ArtifactInfo artifactInfo,
                              final ArtifactEntry artifactEntry);

    void updateDocument(final ArtifactInfo artifactInfo,
                        final Document doc);
}
