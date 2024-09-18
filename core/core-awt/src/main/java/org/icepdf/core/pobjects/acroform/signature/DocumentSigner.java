package org.icepdf.core.pobjects.acroform.signature;

import org.bouncycastle.cms.CMSException;
import org.bouncycastle.operator.OperatorCreationException;
import org.icepdf.core.io.CountingOutputStream;
import org.icepdf.core.pobjects.*;
import org.icepdf.core.pobjects.acroform.SignatureDictionary;
import org.icepdf.core.pobjects.security.SecurityManager;
import org.icepdf.core.pobjects.structure.CrossReferenceRoot;
import org.icepdf.core.pobjects.structure.exceptions.CrossReferenceStateException;
import org.icepdf.core.pobjects.structure.exceptions.ObjectStateException;
import org.icepdf.core.util.Library;
import org.icepdf.core.util.updater.writeables.BaseWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DocumentSigner does the awkward task of populating a SignatureDictionary's /content and /ByteRange entries with
 * valid values.  The updated SignatureDictionary is inserted back into the file using he same byte footprint but
 * contains a singed digest and respective offset of the signed content.
 */
public class DocumentSigner {

    public static int PLACEHOLDER_PADDING_LENGTH = 30000;
    public static int PLACEHOLDER_BYTE_OFFSET_LENGTH = 9;

    /**
     * The given Document instance will be singed using signatureDictionary location and written to the specified
     * output stream.
     *
     * @param document            document contents to be signed
     * @param outputFile          output file for singed document output
     * @param signatureDictionary dictionary to update signer information
     * @throws IOException
     * @throws CrossReferenceStateException
     * @throws ObjectStateException
     * @throws UnrecoverableKeyException
     * @throws CertificateException
     * @throws KeyStoreException
     * @throws NoSuchAlgorithmException
     * @throws OperatorCreationException
     * @throws CMSException
     */
    public static void signDocument(Document document, File outputFile, SignatureDictionary signatureDictionary)
            throws IOException, CrossReferenceStateException, ObjectStateException, UnrecoverableKeyException,
            CertificateException, KeyStoreException, NoSuchAlgorithmException, OperatorCreationException, CMSException {
        try (final RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");) {
            Library library = document.getCatalog().getLibrary();
            int signatureDictionaryOffset = library.getOffset(signatureDictionary.getPObjectReference());

            StateManager stateManager = document.getStateManager();
            SecurityManager securityManager = document.getSecurityManager();
            CrossReferenceRoot crossReferenceRoot = stateManager.getCrossReferenceRoot();

            // write out the securityDictionary, so we can make the necessary edits for setting up signing
            String rawSignatureDiciontary = writeSignatureDictionary(crossReferenceRoot, securityManager,
                    signatureDictionary);
            int signatureDictionaryLength = rawSignatureDiciontary.length();

            // figure out byte offset around the content hex string
            final FileChannel fc = raf.getChannel();
            fc.position(0);
            long fileLength = fc.size();

            // find byte offset of the start of content hex string
            int firstStart = 0;
            String contents = "/Contents <";
            int firstOffset = signatureDictionaryOffset + rawSignatureDiciontary.indexOf(contents) + contents.length();
            int secondStart = firstOffset + PLACEHOLDER_PADDING_LENGTH;
            int secondOffset = (int) fileLength - secondStart; // just awkward, but should 32bit max.

            // find length of the new array.
            List byteRangeArray = List.of(firstStart, firstOffset, secondStart, secondOffset);
            String byteRangeDump = writeByteOffsets(crossReferenceRoot, securityManager, byteRangeArray);
            // adjust the second start, we will make sure the padding zeros on the /contents hex string adjust
            // accordingly
            int byteRangeDelta = byteRangeDump.length() - PLACEHOLDER_BYTE_OFFSET_LENGTH;
            secondStart -= byteRangeDelta;
            secondOffset = (int) fileLength - secondStart;
            byteRangeArray = List.of(firstStart, firstOffset, secondStart, secondOffset);
            byteRangeDump = writeByteOffsets(crossReferenceRoot, securityManager, byteRangeArray);
            // update /ByteRange

            rawSignatureDiciontary = rawSignatureDiciontary.replace("/ByteRange [0 0 0 0]",
                    "/ByteRange " + byteRangeDump);

            // update /contents with adjusted length for byteRange offset
            Pattern pattern = Pattern.compile("/Contents <([A-Fa-f0-9]+)>");
            Matcher matcher = pattern.matcher(rawSignatureDiciontary);
            int placeholderPadding = byteRangeDelta;
            if (placeholderPadding % 2 != 0) {
                placeholderPadding++;
            }
            rawSignatureDiciontary =
                    matcher.replaceFirst("/Contents <" + generateContentsPlaceholder(placeholderPadding) + ">");

            // write the altered signature dictionary
            fc.position(signatureDictionaryOffset);
            int writtenn = fc.write(ByteBuffer.wrap(rawSignatureDiciontary.getBytes()));

            // digest the file creating the content signature
            ByteBuffer preContent = ByteBuffer.allocateDirect(firstOffset);
            ByteBuffer postContent = ByteBuffer.allocateDirect(secondOffset);
            fc.position(firstStart);
            fc.read(preContent);
            fc.position(secondStart);
            fc.read(postContent);
            byte[] combined = new byte[preContent.limit() + postContent.limit()];
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            preContent.flip();
            postContent.flip();
            buffer.put(preContent);
            buffer.put(postContent);

            byte[] signature = signatureDictionary.getSignedData(combined);
            String hexContent = HexStringObject.encodeHexString(signature);
            if (hexContent.length() < PLACEHOLDER_PADDING_LENGTH) {
                int padding = PLACEHOLDER_PADDING_LENGTH - byteRangeDelta - hexContent.length();
                // make sure the contents stream is an even hex number
                if (byteRangeDelta % 2 != 0) {
                    padding--;
                }
                hexContent = hexContent + "0".repeat(Math.max(0, padding));
            } else {
                throw new IllegalStateException("signature content is larger than placeholder");
            }
            // update /contents with signature
            rawSignatureDiciontary = matcher.replaceFirst("/Contents <" + hexContent + ">");

            // write the altered signature dictionary
            fc.position(signatureDictionaryOffset);
            int count = fc.write(ByteBuffer.wrap(rawSignatureDiciontary.getBytes()));

            // make sure the object length didn't change
            if (count != signatureDictionaryLength) {
                throw new IllegalStateException("Signature dictionary length change original " + count +
                        " new " + signatureDictionaryLength);
            }

        }
    }

    public static String writeSignatureDictionary(CrossReferenceRoot crossReferenceRoot,
                                                  SecurityManager securityManager,
                                                  SignatureDictionary signatureDictionary) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        CountingOutputStream objectOutput = new CountingOutputStream(byteArrayOutputStream);
        BaseWriter writer = new BaseWriter(crossReferenceRoot, securityManager, objectOutput, 0l);
        writer.initializeWriters();
        writer.writePObject(new PObject(signatureDictionary, signatureDictionary.getPObjectReference()));
        String objectDump = byteArrayOutputStream.toString(StandardCharsets.UTF_8);
        objectOutput.close();
        return objectDump;
    }

    public static String writeByteOffsets(CrossReferenceRoot crossReferenceRoot, SecurityManager securityManager,
                                          List offsets) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        CountingOutputStream objectOutput = new CountingOutputStream(byteArrayOutputStream);
        BaseWriter writer = new BaseWriter(crossReferenceRoot, securityManager, objectOutput, 0l);
        writer.initializeWriters();
        writer.writeValue(new PObject(offsets, new Reference(1, 0)), objectOutput);
        String objectDump = byteArrayOutputStream.toString(StandardCharsets.UTF_8);
        objectOutput.close();
        return objectDump;
    }

    public static String generateContentsPlaceholder() {
        return generateContentsPlaceholder(0);
    }

    public static String generateContentsPlaceholder(int reductionAdjustment) {
        int capacity = PLACEHOLDER_PADDING_LENGTH - reductionAdjustment;
        StringBuilder paddedZeros = new StringBuilder(capacity);
        paddedZeros.append("0".repeat(Math.max(0, capacity)));
        return paddedZeros.toString();
    }


}
