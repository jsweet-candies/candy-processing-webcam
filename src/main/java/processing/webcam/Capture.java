package processing.webcam;

import static def.dom.Globals.window;
import static def.js.Globals.isNaN;
import static def.js.Globals.parseInt;
import static def.processing.core.NativeFeatures.createDomElement;
import static def.processing.core.NativeFeatures.freeObjectMemory;
import static def.processing.core.NativeFeatures.getDomElementById;
import static def.processing.core.NativeFeatures.getUserMedia;
import static def.processing.core.NativeFeatures.queryDomElementBySelector;
import static jsweet.util.Lang.$insert;
import static jsweet.util.Lang.$map;
import static jsweet.util.Lang.await;
import static jsweet.util.StringTypes._2d;
import static jsweet.util.StringTypes.canvas;
import static jsweet.util.StringTypes.video;

import java.util.function.Consumer;

import def.dom.CanvasRenderingContext2D;
import def.dom.HTMLCanvasElement;
import def.dom.HTMLElement;
import def.dom.HTMLVideoElement;
import def.dom.ImageData;
import def.dom.MediaStream;
import def.dom.MediaStreamTrack;
import def.js.ArrayBuffer;
import def.js.Promise;
import def.js.RegExp;
import def.js.RegExpExecArray;
import def.js.Uint8Array;
import def.js.Uint8ClampedArray;
import def.processing.core.PApplet;
import def.processing.core.PApplet.PImageLike;
import def.processing.core.PImage;
import jsweet.lang.Async;
import jsweet.lang.Interface;

@Interface
abstract class Dimension {
	int width;
	int height;
}

public class Capture extends PImageLike {

	private static final boolean LOG_ENABLED = true;

	private static final int DEFAULT_WIDTH = 800;
	private static final int DEFAULT_HEIGHT = 600;

	private static final String CAPTURE_CANVAS_ELEMENT_ID = "ProcessingWebCam__captureCanvas";
	private static final String VIDEO_ELEMENT_ID = "ProcessingWebCam__videoOutput";
	private static final String INITIALIZED_DATA_ATTRIBUTE_NAME = "ProcessingWebCam__initialize";

	private final PApplet applet;
	public final int width;
	public final int height;

	private HTMLVideoElement videoElement;
	private HTMLCanvasElement canvasElement;
	private CanvasRenderingContext2D canvasContext;

	private boolean available;

	/**
	 * Last captured image's data, fully resolved in thread's memory
	 * 
	 * @see #read()
	 */
	public ImageData imageData;

	/**
	 * Last captured image's data from canvas, which can be a proxy if in worker
	 * context.
	 * 
	 * @see ImageData
	 */
	private ImageData capturedImageData;
	private MediaStream mediaStream;
	private Uint8Array imageDataPixels;

	private boolean grabStarted;

	private boolean isWorker;

	/**
	 * @see #imageData
	 */
	public ImageData toImageData() {
		return imageData;
	}

	public Capture(PApplet applet, String dimension) {
		this(applet, decodeDimension(dimension).width, decodeDimension(dimension).height);
	}

	public Capture(PApplet applet, int width, int height) {
		this.applet = applet;
		this.width = width;
		this.height = height;

		this.isWorker = window.$get("WorkerGlobalScope") != null;
		System.out.println("instantiating Capture - isWorker=" + isWorker);

		applet.onExitListeners.add(exitedApplet -> this.releaseResources(exitedApplet));
	}

	@Async
	private Promise<Void> releaseResources(PApplet exitedApplet) {

		MediaStream mediaStreamToRelease = mediaStream;
		HTMLCanvasElement canvasElementToRelease = canvasElement;
		HTMLVideoElement videoElementToRelease = videoElement;

		videoElement = null;
		canvasElement = null;
		canvasContext = null;
		mediaStream = null;

		log("Capture : release resources");
		if (mediaStreamToRelease != null) {
			try {
				log("stopping input media stream");
				MediaStreamTrack[] tracks = mediaStreamToRelease.getTracks();
				int nbTracks = await(applet.nativeFeatures.resolve(tracks.length));
				log("stopping " + nbTracks + " tracks");
				for (int i = 0; i < nbTracks; i++) {
					log("stopping track " + (i));
					MediaStreamTrack track = tracks[i];
					track.stop();
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.err.println("cannot stop input media stream");
			}
		} else {
			log("media stream wasn't started");
		}

		if (canvasElementToRelease != null) {
			log("removing canvas");
			canvasElementToRelease.remove();
		} else {
			log("canvas not found");
		}
		if (videoElementToRelease != null) {
			log("removing video element");
			videoElementToRelease.remove();
		} else {
			log("video element not found");
		}

		return null;
	}

	private void log(String message) {
		if (LOG_ENABLED) {
			System.out.println(getClass().getSimpleName() + ": " + message);
		}
	}

	@Async
	private Promise<Void> initDomElements() {
		log("initDomElements");
		HTMLElement body = applet.nativeFeatures.invoke(queryDomElementBySelector, "body");
		log("initDomElements - body=" + (body == null ? "null" : $insert("typeof body")));

		videoElement = (HTMLVideoElement) applet.nativeFeatures.invoke(getDomElementById, VIDEO_ELEMENT_ID);
		if (await(applet.nativeFeatures.resolve(videoElement)) == null) {
			log("creating video element");
			videoElement = applet.nativeFeatures.invoke(createDomElement, video);
			videoElement.setAttribute("id", VIDEO_ELEMENT_ID);
			videoElement.setAttribute("style", "display:none;");
			videoElement.setAttribute("width", width + "px");
			videoElement.setAttribute("height", height + "px");
			videoElement.setAttribute("autoplay", "true");
			body.appendChild(videoElement);

		} else {
			log("video element found");
		}
		canvasElement = (HTMLCanvasElement) applet.nativeFeatures.invoke(getDomElementById, CAPTURE_CANVAS_ELEMENT_ID);
		if (await(applet.nativeFeatures.resolve(canvasElement)) == null) {
			log("creating canvas element");

			canvasElement = applet.nativeFeatures.invoke(createDomElement, canvas);
			canvasElement.setAttribute("id", CAPTURE_CANVAS_ELEMENT_ID);
			canvasElement.setAttribute("style", "display:none;");
			canvasElement.setAttribute("width", width + "px");
			canvasElement.setAttribute("height", height + "px");
			body.appendChild(canvasElement);
		} else {
			log("canvas element found");
		}
		canvasContext = canvasElement.getContext(_2d);
		await(readImageFromCanvas());

		return null;
	}

	private static Dimension decodeDimension(String dimensionString) {
		Dimension dimension = new Dimension() {
			{
				width = DEFAULT_WIDTH;
				height = DEFAULT_HEIGHT;
			}
		};

		if (dimensionString != null) {
			RegExp sizeRegExp = new RegExp("size=([0-9]+)x([0-9]+)", "g");
			RegExpExecArray result = sizeRegExp.exec(dimensionString);
			if (result.length > 2) {
				int width = parseInt(result.$get(1));
				if (!isNaN(width)) {
					dimension.width = width;
				}
				int height = parseInt(result.$get(2));
				if (!isNaN(height)) {
					dimension.height = height;
				}
			}
		}

		return dimension;
	}

	/**
	 * Preload a PImage accessible after with this.get(int,int) using loadImage()
	 * 
	 * @see #get(int, int)
	 * @see #loadImage()
	 */
	@Async
	public void read() {
		ensureAvailable();

		if (isWorker) {
			if (!grabStarted) {
				grab();
			}
		} else {
			await(readNextFrame());
		}
	}

	@Async
	private void grab() {
		while (started()) {
			grabStarted = true;
			await(readNextFrame());
		}
	}

	@Async
	private Promise<Void> readNextFrame() {
		releasePreviousImageData();

		if (started()) {
			canvasContext.drawImage(videoElement, 0, 0);
			await(readImageFromCanvas());
		} else {
			log("cannot read image - capture stopped");
		}

		return null;
	}

	@Async
	private Void readImageFromCanvas() {
		this.capturedImageData = canvasContext.getImageData(0, 0, width, height);
		ArrayBuffer imageDataPixelsBuffer = await(applet.nativeFeatures.resolve(this.capturedImageData.data.buffer));
		this.imageDataPixels = new Uint8Array(imageDataPixelsBuffer);
		this.imageData = new ImageData(new Uint8ClampedArray(imageDataPixelsBuffer), width, height);

		return null;
	}

	private void releasePreviousImageData() {
		ImageData previousImageData = this.capturedImageData;
		applet.nativeFeatures.resolve(previousImageData).then((resolvedImageData) -> {
			if (resolvedImageData != null) {
				applet.nativeFeatures.invoke(freeObjectMemory, previousImageData);
			}
		});
	}

	/**
	 * Returns pixel at {x,y} of previously captured/read image, or 0 if no image
	 * loaded.
	 * 
	 * @see #read()
	 * @see PImage#get(int, int)
	 */
	@Async
	public int get(int x, int y) {
		if (imageData == null) {
			return 0;
		}

		int pixelIndex = 4 * (x + y * width);
		double red = imageDataPixels.$get(pixelIndex);
		double green = imageDataPixels.$get(pixelIndex + 1);
		double blue = imageDataPixels.$get(pixelIndex + 2);
		double alpha = imageDataPixels.$get(pixelIndex + 3);
		int pixelRgb = ((int) alpha << 24) + ((int) red << 16) + ((int) green << 8) + ((int) blue);

		return pixelRgb;
	}

	public void start() {
		if (videoElement == null || videoElement.dataset.$get(INITIALIZED_DATA_ATTRIBUTE_NAME) != "true") {
			initDomElements() //
					.then(__ -> {
						Consumer<MediaStream> gotStream = stream -> gotStream(stream);
						Consumer<Object> noStream = error -> noStream(error);
						Promise<MediaStream> getUserMediaPromise = applet.nativeFeatures.invoke(getUserMedia,
								$map("video", true));
						getUserMediaPromise.then(gotStream).Catch(noStream);

						if (started()) {
							videoElement.dataset.$set(INITIALIZED_DATA_ATTRIBUTE_NAME, "true");
						}
					});
		}
	}

	private boolean started() {
		return videoElement != null && canvasElement != null;
	}

	private void gotStream(MediaStream stream) {
		this.mediaStream = stream;
		log("requesting video play");
		if (started()) {
			videoElement.$set("srcObject", stream);
			videoElement.onerror = (error) -> {
				streamError(error);

				return null;
			};
			videoElement.onplay = (__) -> {
				log("play started, video capture available");
				available = true;
				return null;
			};
		} else {
			streamError("capture was stopped");
		}
	}

	private void noStream(Object error) {
		System.err.println("an error occurred while accessing camera: " + error);
	}

	private void streamError(Object error) {
		System.err.println("an error occurred while streaming camera: " + error);
	}

	private void ensureAvailable() {
		if (!available()) {
			throw new Error("camera not available");
		}
	}

	public boolean available() {
		return available;
	}

	/**
	 * @return Only one value "name=Unknown,size=800x600,fps=30"
	 */
	public static String[] list() {
		return new String[] { "name=Unknown,size=800x600,fps=30" };
	}
}
