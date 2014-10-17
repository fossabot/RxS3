package pl.codewise.amazon.client;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import pl.codewise.amazon.client.auth.AWSSignatureCalculatorFactory;
import pl.codewise.amazon.client.xml.ConsumeBytesParser;
import pl.codewise.amazon.client.xml.ErrorResponseParser;
import pl.codewise.amazon.client.xml.GenericResponseParser;
import pl.codewise.amazon.client.xml.ListResponseParser;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static pl.codewise.amazon.client.RestUtils.createQueryString;
import static pl.codewise.amazon.client.RestUtils.escape;

@SuppressWarnings("UnusedDeclaration")
public class AsyncCodewiseS3Client implements Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCodewiseS3Client.class);

	public static final String S3_LOCATION = "s3.amazonaws.com";
	private static final String S3_URL = "http://" + S3_LOCATION;

	private final AsyncHttpClient httpClient;

	private final ListResponseParser listResponseParser;
	private final ErrorResponseParser errorResponseParser;

	private final AWSSignatureCalculatorFactory signatureCalculators;

	public AsyncCodewiseS3Client(AWSCredentials credentials, HttpClientFactory httpClientFactory) {
		this.httpClient = httpClientFactory.getHttpClient();

		try {
			XmlPullParserFactory pullParserFactory = XmlPullParserFactory.newInstance();
			pullParserFactory.setNamespaceAware(false);

			listResponseParser = new ListResponseParser(pullParserFactory);
			errorResponseParser = new ErrorResponseParser(pullParserFactory);
		} catch (XmlPullParserException e) {
			throw new RuntimeException("Unable to initialize xml pull parser factory", e);
		}

		signatureCalculators = new AWSSignatureCalculatorFactory(credentials);
	}

	public Observable<byte[]> putObject(String bucketName, String key, InputStream inputStream, ObjectMetadata metadata) throws IOException {
		String virtualHost = getVirtualHost(bucketName);

		Request request = httpClient.preparePut(S3_URL + "/" + key)
				.setVirtualHost(virtualHost)
				.setSignatureCalculator(signatureCalculators.getPutSignatureCalculator(bucketName))
				.setBody(inputStream)
				.setContentLength((int) metadata.getContentLength())
				.setHeader("Content-MD5", metadata.getContentMD5())
				.setHeader("Content-Type", metadata.getContentType())
				.build();

		return retrieveResult(request, ConsumeBytesParser.getInstance());
	}

	public Observable<byte[]> putObject(PutObjectRequest request) throws IOException {
		return putObject(request.getBucketName(), request.getKey(), request.getInputStream(), request.getMetadata());
	}

	public Observable<ObjectListing> listObjects(String bucketName) {
		return listObjects(bucketName, null);
	}

	public Observable<ObjectListing> listObjects(String bucketName, String prefix) {
		String virtualHost = getVirtualHost(bucketName);
		String queryString = createQueryString(prefix, null, null, null);

		Request request = httpClient.prepareGet(S3_URL + "/?" + queryString)
				.setVirtualHost(virtualHost)
				.setSignatureCalculator(signatureCalculators.getListSignatureCalculator(bucketName))
				.build();

		return retrieveResult(request, listResponseParser);
	}

	public Observable<ObjectListing> listNextBatchOfObjects(ObjectListing objectListing) {
		if (!objectListing.isTruncated()) {
			ObjectListing emptyListing = new ObjectListing();
			emptyListing.setBucketName(objectListing.getBucketName());
			emptyListing.setDelimiter(objectListing.getDelimiter());
			emptyListing.setMarker(objectListing.getNextMarker());
			emptyListing.setMaxKeys(objectListing.getMaxKeys());
			emptyListing.setPrefix(objectListing.getPrefix());
			emptyListing.setTruncated(false);

			return Observable.just(emptyListing);
		}

		return listObjects(new ListObjectsRequest(
				objectListing.getBucketName(),
				objectListing.getPrefix(),
				objectListing.getNextMarker(),
				objectListing.getDelimiter(),
				objectListing.getMaxKeys()
		));
	}

	public Observable<ObjectListing> listObjects(ListObjectsRequest listObjectsRequest) {
		String virtualHost = getVirtualHost(listObjectsRequest.getBucketName());
		String queryString = createQueryString(listObjectsRequest);

		Request request = httpClient.prepareGet(S3_URL + "/?" + queryString)
				.setVirtualHost(virtualHost)
				.setSignatureCalculator(signatureCalculators.getListSignatureCalculator(listObjectsRequest.getBucketName()))
				.build();

		return retrieveResult(request, listResponseParser);
	}

	public Observable<byte[]> getObject(String bucketName, String location) throws IOException {
		String virtualHost = getVirtualHost(bucketName);
		String url = S3_URL + "/" + escape(location);

		Request request = httpClient.prepareGet(url)
				.setVirtualHost(virtualHost)
				.setSignatureCalculator(signatureCalculators.getGetSignatureCalculator(bucketName))
				.build();

		return retrieveResult(request, ConsumeBytesParser.getInstance());
	}

	@Override
	public void close() {
		httpClient.close();
	}

	private String getVirtualHost(String bucketName) {
		return bucketName + "." + S3_LOCATION;
	}

	private <T> Observable<T> retrieveResult(final Request request, final GenericResponseParser<T> responseParser) {
		return Observable.create((Subscriber<? super T> subscriber) -> {
			try {
				httpClient.executeRequest(request, new SubscriptionCompletionHandler<>(subscriber, responseParser));
			} catch (IOException e) {
				subscriber.onError(e);
			}
		});
	}

	private class SubscriptionCompletionHandler<T> extends AsyncCompletionHandler<T> {

		private final Subscriber<? super T> subscriber;
		private final GenericResponseParser<T> responseParser;

		public SubscriptionCompletionHandler(Subscriber<? super T> subscriber, GenericResponseParser<T> responseParser) {
			this.subscriber = subscriber;
			this.responseParser = responseParser;
		}

		@Override
		public T onCompleted(Response response) throws IOException {
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Amazon response '{}'", response.getResponseBody());
			}

			if (subscriber.isUnsubscribed()) {
				return ignoreReturnValue();
			}

			if (!emitExceptionIfUnsuccessful(response, subscriber)) {
				try {
					Optional<T> result = responseParser.parse(response);
					if (result.isPresent()) {
						subscriber.onNext(result.get());
					}

					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}

			return ignoreReturnValue();
		}

		@Override
		public void onThrowable(Throwable t) {
			LOGGER.error("Error while processing S3 request", t);
			subscriber.onError(t);
		}

		private boolean emitExceptionIfUnsuccessful(Response response, Observer<?> observer) throws IOException {
			if (response.getStatusCode() != 200) {
				observer.onError(errorResponseParser.parse(response).get().build());
				return true;
			}

			return false;
		}

		T ignoreReturnValue() {
			return null;
		}
	}
}
