package processing.webcam;

import static def.js.Globals.isNaN;
import static def.js.Globals.parseInt;
import static def.processing.core.NativeFeatures.createDomElement;
import static def.processing.core.NativeFeatures.freeObjectMemory;
import static def.processing.core.NativeFeatures.getDomElementById;
import static def.processing.core.NativeFeatures.getUserMedia;
import static def.processing.core.NativeFeatures.queryDomElementBySelector;
import static jsweet.util.Lang.$insert;
import static jsweet.util.Lang.$map;
import static jsweet.util.Lang.array;
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
import def.js.Promise;
import def.js.RegExp;
import def.js.RegExpExecArray;
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

// TODO : add finalizer!
public class Capture extends PImageLike {

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
	 * Last captured image's data
	 * 
	 * @see #read()
	 */
	public ImageData imageData;
	private MediaStream mediaStream;

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

		applet.onExitListeners.add(exitedApplet -> this.releaseResources(exitedApplet));
	}

	@Async
	private void releaseResources(PApplet exitedApplet) {
		try {
			array(mediaStream.getTracks()).forEach(track -> track.stop());
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("cannot stop input media stream");
		}

		if (await(applet.nativeFeatures.resolve(canvasElement)) != null) {
			canvasElement.remove();
		}
		if (await(applet.nativeFeatures.resolve(videoElement)) != null) {
			videoElement.remove();
		}
		videoElement = null;
		canvasElement = null;
		canvasContext = null;
	}

	@Async
	private Promise<Void> initDomElements() {
		System.out.println("initDomElements");
		HTMLElement body = applet.nativeFeatures.invoke(queryDomElementBySelector, "body");
		System.out.println("initDomElements - body=" + (body == null ? "null" : $insert("typeof body")));

		videoElement = (HTMLVideoElement) applet.nativeFeatures.invoke(getDomElementById, VIDEO_ELEMENT_ID);
		if (await(applet.nativeFeatures.resolve(videoElement)) == null) {
			videoElement = applet.nativeFeatures.invoke(createDomElement, video);
			videoElement.setAttribute("id", VIDEO_ELEMENT_ID);
			videoElement.setAttribute("style", "display:none;");
			videoElement.setAttribute("width", width + "px");
			videoElement.setAttribute("height", height + "px");
			videoElement.setAttribute("autoplay", "true");
			body.appendChild(videoElement);

		}
		canvasElement = (HTMLCanvasElement) applet.nativeFeatures.invoke(getDomElementById, CAPTURE_CANVAS_ELEMENT_ID);
		if (await(applet.nativeFeatures.resolve(canvasElement)) == null) {
			canvasElement = applet.nativeFeatures.invoke(createDomElement, canvas);
			canvasElement.setAttribute("id", CAPTURE_CANVAS_ELEMENT_ID);
			canvasElement.setAttribute("style", "display:none;");
			canvasElement.setAttribute("width", width + "px");
			canvasElement.setAttribute("height", height + "px");
			body.appendChild(canvasElement);
		}
		canvasContext = canvasElement.getContext(_2d);
		imageData = canvasContext.getImageData(0, 0, width, height);

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
	public void read() {
		ensureAvailable();

		releasePreviousImageData();

		canvasContext.drawImage(videoElement, 0, 0);
		this.imageData = canvasContext.getImageData(0, 0, width, height);
	}

	private void releasePreviousImageData() {
		ImageData previousImageData = this.imageData;
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
	public int get(int x, int y) {
		if (imageData == null) {
			return 0;
		}

		int pixelIndex = 4 * (x + y * width);
		int red = (int) imageData.data[pixelIndex]; // red color
		int green = (int) imageData.data[pixelIndex + 1]; // green color
		int blue = (int) imageData.data[pixelIndex + 2]; // blue color
		int alpha = (int) imageData.data[pixelIndex + 3]; // alpha
		int pixelRgb = (red << 24) + (green << 16) + (blue << 8) + (alpha);

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

						videoElement.dataset.$set(INITIALIZED_DATA_ATTRIBUTE_NAME, "true");
					});
		}
	}

	private void gotStream(MediaStream stream) {
		this.mediaStream = stream;
		System.out.println("requesting video play");
		videoElement.$set("srcObject", stream);
		videoElement.onerror = (error) -> {
			streamError(error);

			return null;
		};
		videoElement.onplay = (__) -> {
			System.out.println("play started, video capture available");
			available = true;
			return null;
		};
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
