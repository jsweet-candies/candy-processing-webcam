package processing.webcam;

import static def.dom.Globals.document;
import static def.dom.Globals.navigator;
import static jsweet.util.Lang.$map;
import static jsweet.util.Lang.object;
import static jsweet.util.StringTypes._2d;
import static jsweet.util.StringTypes.canvas;
import static jsweet.util.StringTypes.video;

import java.util.function.Consumer;

import def.dom.CanvasRenderingContext2D;
import def.dom.HTMLCanvasElement;
import def.dom.HTMLVideoElement;
import def.dom.ImageData;
import def.js.Promise;
import def.processing.core.PApplet;
import def.processing.core.PConstants;
import def.processing.core.PImage;

public class Capture {

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

	private PImage capturedImage;

	public Capture(PApplet applet, int width, int height) {
		this.applet = applet;
		this.width = width;
		this.height = height;

		videoElement = (HTMLVideoElement) document.getElementById(VIDEO_ELEMENT_ID);
		if (videoElement == null) {
			videoElement = document.createElement(video);
			videoElement.setAttribute("id", VIDEO_ELEMENT_ID);
			videoElement.setAttribute("style", "display:none;");
			videoElement.setAttribute("width", width + "px");
			videoElement.setAttribute("height", height + "px");
			videoElement.setAttribute("autoplay", "true");
			document.body.appendChild(videoElement);

		}
		canvasElement = (HTMLCanvasElement) document.getElementById(CAPTURE_CANVAS_ELEMENT_ID);
		if (canvasElement == null) {
			canvasElement = document.createElement(canvas);
			canvasElement.setAttribute("id", CAPTURE_CANVAS_ELEMENT_ID);
			canvasElement.setAttribute("style", "display:none;");
			canvasElement.setAttribute("width", width + "px");
			canvasElement.setAttribute("height", height + "px");
			document.body.appendChild(canvasElement);
		}
		canvasContext = canvasElement.getContext(_2d);
	}

	public void drawOnApplet() {
		ensureAvailable();

		HTMLCanvasElement appletCanvas = applet.externals.canvas;
		CanvasRenderingContext2D appletRenderingContext = appletCanvas.getContext(_2d);
		appletRenderingContext.drawImage(videoElement, 0, 0);
	}

	/**
	 * Preload a PImage accessible after with this.get(int,int) using loadImage()
	 * 
	 * @see #get(int, int)
	 * @see #loadImage()
	 */
	public void read() {
		this.capturedImage = loadImage();
	}

	/**
	 * Returns pixel at {x,y} of previously captured/read image, or 0 if no image
	 * loaded.
	 * 
	 * @see #read()
	 * @see PImage#get(int, int)
	 */
	public int get(int x, int y) {
		if (capturedImage == null) {
			return 0;
		}

		return this.capturedImage.get(x, y);
	}

	public PImage loadImage() {
		ensureAvailable();

		canvasContext.drawImage(videoElement, 0, 0);
		PImage image = applet.createImage(width, height, PConstants.ARGB);
		ImageData imageData = canvasContext.getImageData(0, 0, width, height);
		image.fromImageData(imageData);
		return image;
	}

	public void start() {

		if (videoElement.dataset.$get(INITIALIZED_DATA_ATTRIBUTE_NAME) != "true") {

			def.js.Object mediaDevices = object(navigator).$get("mediaDevices");
			if (mediaDevices == null || mediaDevices.$get("getUserMedia") == null) {
				noStream("navigator.mediaDevices.getUserMedia not found");
				return;
			}

			Consumer<Object> gotStream = stream -> gotStream(stream);
			Consumer<Object> noStream = error -> noStream(error);
			Promise<Object> getUserMediaPromise = mediaDevices //
					.$invoke("getUserMedia", $map("video", true));
			getUserMediaPromise.then(gotStream).Catch(noStream);

			videoElement.dataset.$set(INITIALIZED_DATA_ATTRIBUTE_NAME, "true");
		}
	}

	private void gotStream(Object stream) {
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
	 * @return Only one value "unknown"
	 */
	public String[] list() {
		return new String[] { "unknown" };
	}
}
