package pl.codewise.amazon.client;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.googlecode.catchexception.CatchException;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.testng.annotations.*;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.observers.TestSubscriber;
import rx.subjects.PublishSubject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.slf4j.LoggerFactory.getLogger;
import static pl.codewise.amazon.client.AsyncS3ClientAssertions.assertThat;

public class AsyncS3ClientTest {

    private static final Logger LOGGER = getLogger(AsyncS3ClientTest.class);

    private static final String ACCESS_KEY_PROPERTY_NAME = "s3.accessKey";
    private static final String SECRET_KEY_PROPERTY_NAME = "s3.secretKey";
    private static final String EMPTY_CREDENTIAL = "empty";

    private static final String BUCKET_NAME_PROPERTY_NAME = "s3.bucketName";
    private static final String DEFAULT_BUCKET_NAME = "async-client-test";

    private String bucketName;
    BasicAWSCredentials credentials;

    private TestS3Object PL = TestS3Object.withNameAndRandomMetadata("COUNTRY_BY_DATE/2014/05/PL", 2);
    private TestS3Object US = TestS3Object.withNameAndRandomMetadata("COUNTRY_BY_DATE/2014/05/US", 3);
    private TestS3Object CZ = TestS3Object.withNameAndRandomMetadata("COUNTRY_BY_DATE/2014/06/CZ", 0);
    private TestS3Object UK = TestS3Object.withNameAndRandomMetadata("COUNTRY_BY_DATE/2014/07/UK", 1);

    ClientConfiguration configuration;

    List<String> fieldsToIgnore = new ArrayList<>();

    private AmazonS3Client amazonS3Client;
    private AsyncS3Client client;

    @BeforeClass
    public void setLocales() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DateTimeZone.setDefault(DateTimeZone.UTC);
        Locale.setDefault(Locale.US);
    }

    @BeforeClass(dependsOnMethods = "setLocales")
    public void setUpCredentialsAndBucketName() {
        String accessKey = System.getProperty(ACCESS_KEY_PROPERTY_NAME, EMPTY_CREDENTIAL);
        String secretKey = System.getProperty(SECRET_KEY_PROPERTY_NAME, EMPTY_CREDENTIAL);

        credentials = new BasicAWSCredentials(accessKey, secretKey);
        amazonS3Client = new AmazonS3Client(credentials);

        if (EMPTY_CREDENTIAL.equals(accessKey) || EMPTY_CREDENTIAL.equals(secretKey)) {
            LOGGER.info("No amazon configuration was found. Assuming fake S3 listens on port 12345");

            amazonS3Client.setEndpoint("http://localhost:12345");
            configuration = ClientConfiguration
                    .builder()
                    .connectTo("localhost:12345")
                    .useCredentials(credentials)
                    .build();
        } else {
            configuration = ClientConfiguration
                    .builder()
                    .useCredentials(credentials)
                    .build();

            LOGGER.info("Found amazon configuration. Using real S3");
        }

        client = new AsyncS3Client(configuration, HttpClientFactory.defaultFactory());
        bucketName = System.getProperty(BUCKET_NAME_PROPERTY_NAME, DEFAULT_BUCKET_NAME);
    }

    @BeforeClass(dependsOnMethods = "setUpCredentialsAndBucketName")
    public void setUpS3Contents() throws IOException {
        PL.putToS3(amazonS3Client, bucketName);
        US.putToS3(amazonS3Client, bucketName);
        CZ.putToS3(amazonS3Client, bucketName);
        UK.putToS3(amazonS3Client, bucketName);
    }

    @AfterClass
    public void tearDown() {
        PL.deleteFromS3(amazonS3Client, bucketName);
        US.deleteFromS3(amazonS3Client, bucketName);
        CZ.deleteFromS3(amazonS3Client, bucketName);
        UK.deleteFromS3(amazonS3Client, bucketName);
    }

    @BeforeMethod
    public void beforeTest() {
        Awaitility.await().atMost(Duration.TEN_SECONDS).until(() -> {
                    System.out.println("Acquired connections " + client.acquiredConnections());
                    assertThat(client.acquiredConnections()).isEqualTo(0);
                }
        );
    }

    @AfterMethod
    public void afterTest() {
        Awaitility.await().atMost(Duration.TEN_SECONDS).until(() -> {
                    System.out.println("Acquired connections " + client.acquiredConnections());
                    assertThat(client.acquiredConnections()).isEqualTo(0);
                }
        );
    }

    @Test(enabled = false)
    public void shouldListObjectsInBucket() {
        // When
        Observable<ObjectListing> listing = client.listObjects(bucketName);
        ObjectListing amazonListing = amazonS3Client.listObjects(bucketName);

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing);
    }

    @Test(enabled = false)
    public void shouldListObjects() {
        // When
        Observable<ObjectListing> listing = client.listObjects(bucketName, "COUNTRY_BY_DATE/2014/");
        ObjectListing amazonListing = amazonS3Client.listObjects(bucketName, "COUNTRY_BY_DATE/2014/");

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing);
    }

    @Test(enabled = false)
    public void shouldListObjectsWhenUsingRequest() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/05/");

        // When
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing);
    }

    @Test(enabled = false)
    public void shouldListObjectBatches() {
        // When & Then
        PublishSubject<ObjectListing> inProgressSubject = PublishSubject.create();
        PublishSubject<ObjectListing> completedSubject = PublishSubject.create();

        Observable<ObjectListing> listing = Observable.unsafeCreate((Subscriber<? super ObjectListing> subscriber) -> client.listObjects(bucketName, "COUNTRY_BY_DATE/2014/05/", subscriber));
        ObjectListing amazonListing = amazonS3Client.listObjects(bucketName, "COUNTRY_BY_DATE/2014/05/");

        inProgressSubject.subscribe(objectListing -> {
            completedSubject.onNext(objectListing);
            if (!objectListing.isTruncated()) {
                completedSubject.onCompleted();
            } else {
                client.listNextBatchOfObjects(objectListing).subscribe(inProgressSubject::onNext);
            }
        }, completedSubject::onError, completedSubject::onCompleted);

        listing.subscribe(inProgressSubject);

        assertThat(completedSubject)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldListObjectBatchesWhenStartingWithARequest() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/06/");

        // When & Then
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        while (amazonListing.isTruncated()) {
            assertThat(listing).isEqualTo(amazonListing);
            listing = client.listNextBatchOfObjects(listing.toBlocking().single());
            amazonListing = amazonS3Client.listNextBatchOfObjects(amazonListing);
        }

        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing).isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldListObjectWithMaxKeysLimit() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/");
        request.setMaxKeys(2);

        // When
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isTruncated()
                .hasSize(2);
    }

    @Test(enabled = false)
    public void shouldListObjectBatchesWhenUsingRequest() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setMaxKeys(2);
        request.setPrefix("COUNTRY_BY_DATE/2014/");

        // When & Then
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        while (amazonListing.isTruncated()) {
            assertThat(listing)
                    .ignoreFields(fieldsToIgnore)
                    .isEqualTo(amazonListing);

            listing = client.listNextBatchOfObjects(listing.toBlocking().single());
            amazonListing = amazonS3Client.listNextBatchOfObjects(amazonListing);
        }

        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldReturnEmptyListingWhenNotTruncated() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/");

        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();

        // When
        listing = client.listNextBatchOfObjects(listing.toBlocking().single());
        amazonListing = amazonS3Client.listNextBatchOfObjects(amazonListing);

        // Then
        assertThat(listing).isEqualTo(amazonListing).isNotNull();
    }

    @Test(enabled = false)
    public void shouldListCommonPrefixes_ContainingFiles() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/05/");
        request.setDelimiter("/");

        // When
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldListCommonPrefixesInBatches_ContainingFiles() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/05/");
        request.setMaxKeys(1);
        request.setDelimiter("/");

        // When
        ObjectListing amazonListing = amazonS3Client.listObjects(request);
        Observable<ObjectListing> listing = client.listObjects(request);

        // Then
        while (amazonListing.isTruncated()) {
            ObjectListing objectListing = listing.toBlocking().single();

            assertThat(objectListing)
                    .ignoreFields(fieldsToIgnore)
                    .isEqualTo(amazonListing)
                    .isTruncated();

            amazonListing = amazonS3Client.listNextBatchOfObjects(amazonListing);
            listing = client.listNextBatchOfObjects(objectListing);
        }

        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldListCommonPrefixes_ContainingDirectories() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/");
        request.setDelimiter("/");

        // When
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        // Then
        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldListCommonPrefixesInBatches_ContainingDirectories() {
        // Given
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix("COUNTRY_BY_DATE/2014/");
        request.setMaxKeys(1);
        request.setDelimiter("/");

        // When
        Observable<ObjectListing> listing = client.listObjects(request);
        ObjectListing amazonListing = amazonS3Client.listObjects(request);

        // Then
        while (amazonListing.isTruncated()) {
            ObjectListing objectListing = listing.toBlocking().single();

            assertThat(objectListing)
                    .ignoreFields(fieldsToIgnore)
                    .isEqualTo(amazonListing)
                    .isTruncated();

            amazonListing = amazonS3Client.listNextBatchOfObjects(amazonListing);
            listing = client.listNextBatchOfObjects(objectListing);
        }

        assertThat(listing)
                .ignoreFields(fieldsToIgnore)
                .isEqualTo(amazonListing)
                .isNotTruncated();
    }

    @Test(enabled = false)
    public void shouldPutObject() throws IOException {
        // Given
        String objectName = RandomStringUtils.randomAlphanumeric(55);
        byte[] data = RandomStringUtils.randomAlphanumeric(10 * 1024).getBytes();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType("application/octet-stream");
        metadata.setContentMD5(getBase64EncodedMD5Hash(data));

        TestSubscriber<Object> subscriber1 = new TestSubscriber<>();
        TestSubscriber<Object> subscriber2 = new TestSubscriber<>();
        TestSubscriber<Object> subscriber3 = new TestSubscriber<>();

        // When
        Observable<?> observable = client.putObject(bucketName, objectName, data, metadata);
        observable.subscribe(subscriber1);

        // Then
        subscriber1.awaitTerminalEvent();
        subscriber1.assertCompleted();
        subscriber1.assertNoValues();

        observable.subscribe(subscriber2);

        subscriber2.awaitTerminalEvent();
        subscriber2.assertCompleted();
        subscriber2.assertNoValues();

        observable.subscribe(subscriber3);

        subscriber3.awaitTerminalEvent();
        subscriber3.assertCompleted();
        subscriber3.assertNoValues();

        S3Object object = amazonS3Client.getObject(bucketName, objectName);
        byte[] actual = IOUtils.toByteArray(object.getObjectContent());

        assertThat(actual).isEqualTo(data);
    }

    @Test(enabled = false)
    public void shouldGetObject() {
        // Given
        String objectName = RandomStringUtils.randomAlphanumeric(55);
        byte[] data = RandomStringUtils.randomAlphanumeric(10 * 1024).getBytes();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType("application/octet-stream");
        metadata.setContentMD5(getBase64EncodedMD5Hash(data));

        amazonS3Client.putObject(bucketName, objectName, new ByteArrayInputStream(data), metadata);

        // When
        InputStream actual = client.getObject(bucketName, objectName)
                .toBlocking()
                .single();

        // Then
        assertThat(actual).hasContentEqualTo(new ByteArrayInputStream(data));
    }

    @Test(enabled = false)
    public void shouldDeleteObject() {
        // Given
        String objectName = RandomStringUtils.randomAlphanumeric(55);
        byte[] data = RandomStringUtils.randomAlphanumeric(10 * 1024).getBytes();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType("application/octet-stream");
        metadata.setContentMD5(getBase64EncodedMD5Hash(data));

        amazonS3Client.putObject(bucketName, objectName, new ByteArrayInputStream(data), metadata);

        // When
        client.deleteObject(bucketName, objectName)
                .toBlocking()
                .singleOrDefault(null);

        // Then
        CatchException.catchException(amazonS3Client).getObject(bucketName, objectName);
        assertThat(CatchException.<Exception>caughtException()).hasMessageContaining("The specified key does not exist");
    }

    private String getBase64EncodedMD5Hash(byte[] packet) {
        byte[] digest = DigestUtils.md5(packet);
        return new String(Base64.encodeBase64(digest));
    }

    private Func1<ObjectListing, Observable<? extends ObjectListing>> continueIfTruncated(AsyncS3Client asyncS3Client) {
        return listing -> {
            if (listing.isTruncated()) {
                Observable<ObjectListing> observable = asyncS3Client.listNextBatchOfObjects(listing)
                        .flatMap(continueIfTruncated(asyncS3Client));

                return Observable.just(listing).concatWith(observable);
            }

            return Observable.just(listing);
        };
    }
}
