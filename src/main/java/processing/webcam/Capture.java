package processing.webcam;

import static def.dom.Globals.alert;
import static def.dom.Globals.document;
import static def.dom.Globals.navigator;
import static jsweet.util.Lang.$map;
import static jsweet.util.Lang.any;
import static jsweet.util.Lang.await;
import static jsweet.util.Lang.object;
import static jsweet.util.StringTypes._2d;
import static jsweet.util.StringTypes.canvas;
import static jsweet.util.StringTypes.img;
import static jsweet.util.StringTypes.video;

import java.util.function.Consumer;

import def.dom.CanvasRenderingContext2D;
import def.dom.Event;
import def.dom.HTMLCanvasElement;
import def.dom.HTMLImageElement;
import def.dom.HTMLVideoElement;
import def.js.Promise;
import def.processing.core.PApplet;
import def.processing.core.PImage;
import jsweet.lang.Async;

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
		HTMLCanvasElement appletCanvas = applet.externals.canvas;
		CanvasRenderingContext2D appletRenderingContext = appletCanvas.getContext(_2d);
		appletRenderingContext.drawImage(videoElement, 0, 0);
	}
	
	@Async
	public PImage loadImage() {
		HTMLImageElement imageElement = await(loadHtmlImage());
		return new PImage(any(imageElement));
	}

	public Promise<HTMLImageElement> loadHtmlImage() {
		canvasContext.drawImage(videoElement, 0, 0);
		HTMLImageElement image = document.createElement(img);
		image.width = canvasElement.width;
		image.height = canvasElement.height;
		return new Promise<HTMLImageElement>((Consumer<HTMLImageElement> resolve, Consumer<Object> reject) -> {
			image.onload = (Event __) -> {
				resolve.accept(image);
				return null;
			};
			image.onerror = (Event event) -> {
				reject.accept(event);
				return null;
			};
			image.src = canvasElement.toDataURL();
		});
	}

	public void init() {

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
		videoElement.$set("srcObject", stream);
		videoElement.onerror = (__) -> {
			streamError();

			return null;
		};
	}

	private void noStream(Object error) {
		System.err.println("an error occurred while accessing camera: " + error);
		alert("No camera available.");
	}

	private void streamError() {
		alert("Camera error.");
	}
}
