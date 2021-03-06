/**
 * Augmented Reality Rubik Cube Wizard
 * 
 * Author: Steven P. Punte (aka Android Steve : android.steve@cl-sw.com)
 * Date:   April 25th 2015
 * 
 * Project Description:
 *   Android application developed on a commercial Smart Phone which, when run on a pair 
 *   of Smart Glasses, guides a user through the process of solving a Rubik Cube.
 *   
 * File Description:
 *   Renders user instruction graphics; in particular wide white arrows to rotate entire
 *   cube or narrower colored arrows to rotate cube edges on a GL Surface.
 *   
 *   Also renders the "Pilot Cube" which appears on the right hand side in
 *   normal mode.  It tracks rotation of the cube, but not translation.
 * 
 * License:
 * 
 *  GPL
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.ar.rubik.gl;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import org.ar.rubik.Constants;
import org.ar.rubik.Constants.AppStateEnum;
import org.ar.rubik.Constants.ColorTileEnum;
import org.ar.rubik.CubePose;
import org.ar.rubik.KalmanFilter;
import org.ar.rubik.MenuAndParams;
import org.ar.rubik.Constants.FaceNameEnum;
import org.ar.rubik.StateModel;
import org.ar.rubik.Util;
import org.ar.rubik.gl.GLArrow.Amount;
import org.ar.rubik.gl.GLCube.Transparency;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;


/**
 *  OpenGL Custom renderer used with GLSurfaceView 
 */
public class GLRenderer implements GLSurfaceView.Renderer {
	
	// Requested Rotation Type
	public enum Rotation { CLOCKWISE, COUNTER_CLOCKWISE, ONE_HUNDRED_EIGHTY };
	
	// Specify direction of arrow.
	public enum Direction { POSITIVE, NEGATIVE };

	// Main State Model
	private StateModel stateModel;

	// Android Application Context
    private Context context;
    
    // OpenGL shader program ID
    int programID;
    
	// GL Objects that can be rendered
	private GLArrow arrowQuarterTurn;
	private GLArrow arrowHalfTurn;
	private GLCube  occlusionGLCube;
    private GLCube  pilotGLCube;
	private GLOverlayCube  overlayGLCube;

    // Projection Matrix:  basically defines a Frustum 
    private float[] mProjectionMatrix = new float[16];



	/**
	 * Constructor with global application stateModel
	 * 
	 * @param stateModel
	 * @param androidActivity 
	 */
	public GLRenderer(StateModel stateModel, Context context) {
		
		this.stateModel = stateModel;
		this.context = context;
	}


	/**
	 * Call back when the surface is first created or re-created
	 * 
	 *  (non-Javadoc)
	 * @see android.opengl.GLSurfaceView.Renderer#onSurfaceCreated(javax.microedition.khronos.opengles.GL10, javax.microedition.khronos.egl.EGLConfig)
	 */
	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
	    
	    // Obtain vertex and fragment shader source text
        String vertexShaderCode = Util.readTextFileFromAssets(context, "simple_vertex_shader.glsl");
        String fragmentShaderCode = Util.readTextFileFromAssets(context, "simple_fragment_shader.glsl");
        
        // Compile shaders
        int vertexShaderID = GLUtil.compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);   
        int fragmentShaderID = GLUtil.compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        
        // Link the shaders together into a final GPU executable.
        programID = GLUtil.linkProgram(vertexShaderID, fragmentShaderID);
        

        // Clear Color 
	    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

	    // Create the GL pilot cube
	    pilotGLCube = new GLCube(stateModel);

	    // Create the GL occlusion cube
	    occlusionGLCube = new GLCube(stateModel);
	    
	    // Create the GL overlay cube
	    overlayGLCube = new GLOverlayCube(stateModel);
	    
	    // Create two arrows: one half turn, one quarter turn.
	    arrowQuarterTurn = new GLArrow(Amount.QUARTER_TURN);
	    arrowHalfTurn = new GLArrow(Amount.HALF_TURN);
	}


	/**
	 * Call back after onSurfaceCreated() or whenever the window's size changes
	 *  (non-Javadoc)
	 *  
	 * @see android.opengl.GLSurfaceView.Renderer#onSurfaceChanged(javax.microedition.khronos.opengles.GL10, int, int)
	 */
	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {

		Log.v(Constants.TAG_CAL, "GLRenderer2.onSurfaceChange: width=" + width + " height=" + height);
		
		stateModel.openGLSize = new Size(width, height);

		if (height == 0) height = 1;   // To prevent divide by zero

		// Adjust the viewport based on geometry changes such as screen rotation
		// This information must match and parallel the Camera Calibration Matrix used by solvePnp() in CubePoseEstimator.
		GLES20.glViewport(0, 0, width, height);
			
		// Projection Matrix primarily represents field-of-view.
		mProjectionMatrix = stateModel.cameraCalibration.calculateOpenGLProjectionMatrix(width, height);
	}



	/**
     * On Draw Frame
     * 
     * Possibly Render:
     *  1) An arrow to rotate the entire cube.
     *  2) An arrow to rotate an edge of the cube.
     *  3) A Transparent Occlusion Cube (i.e., should be observed as exactly over the physical cube).
     *  4) An Pilot Cube off to the right, at a fixed size and location, but with rotation of the physical cube.
     *  5) A Wire Frame Overlay Cube for the purpose of diagnostics
     * 
	 *  (non-Javadoc)
	 * @see android.opengl.GLSurfaceView.Renderer#onDrawFrame(javax.microedition.khronos.opengles.GL10)
	 */
	@Override
	public void onDrawFrame(GL10 unused) {

	    // View Matrix
        final float[] viewMatrix = new float[16];
        
        // Projection View Matrix
        final float[] pvMatrix   = new float[16];
        
        // Model View Projection Matrix
	    final float[] mvpMatrix  = new float[16];
        
        // Enable Depth Testing and Occulsion
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

	    // Draw background color
	    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
	    
	    KalmanFilter kalmanFilter = stateModel.kalmanFilter;
	    if(kalmanFilter == null) {
//	        Log.e(Constants.TAG_OPENGL, "GLRender2.onDrawFrame(): Cannot render, no Kalman Filter");
	    	return;
	    }
	    
	    // Get Cube Pose and check if null: don't render.
	    CubePose cubePose = kalmanFilter.projectState(System.currentTimeMillis());
        if(cubePose == null) {
//            Log.e(Constants.TAG_OPENGL, "GLRender2.onDrawFrame(): Cannot render, no Cube Pose");
        	return;
        }
        
//        Log.e(Constants.TAG_OPENGL, "GLRender2.onDrawFrame(): Good to render");
        
        float[] poseRotationMatrix = computePoseRotationMatrix(cubePose);

	    // Set the camera position (View matrix), a 4x4 matrix is created.
        Matrix.setLookAtM(viewMatrix, 0,
                0,    0,    0,    // Camera Location
                0f,   0f,  -1f,   // Camera points down Z axis.
                0f, 1.0f, 0.0f);  // Specifies rotation of camera: in this case, standard upwards orientation.

	    // Calculate the projection and view transformation
        // pvMatrix = mProjectionMatrix * viewMatrix
	    Matrix.multiplyMM(pvMatrix, 0, mProjectionMatrix, 0, viewMatrix, 0);
	    
	    // Render User Instruction Arrows and possibly Overlay Cube
        if( (MenuAndParams.cubeOverlayDisplay == true) || (stateModel.appState == AppStateEnum.ROTATE_CUBE) || (stateModel.appState == AppStateEnum.ROTATE_FACE) ) {
            
            System.arraycopy(pvMatrix, 0, mvpMatrix, 0, pvMatrix.length);
            
            // Translate Cube per Pose Estimator
            Matrix.translateM(mvpMatrix, 0, 
                    cubePose.x, 
                    cubePose.y, 
                    cubePose.z);
            
                       
            // Rotation Cube per Pose Estimator
            GLUtil.rotateMatrix(mvpMatrix, poseRotationMatrix);
            
            // Rotation Cube per additional requests 
            // =+= Don't use: screws up arrows with present code, and not really important.
            //GLUtil.rotateMatrix(mvpMatrix, stateModel.additionalGLCubeRotation);

            // Scale
            // Not in use since correct calibration was achieved.
//            float scale = (float) MenuAndParams.scaleOffsetParam.value;
//            Matrix.scaleM(mvpMatrix, 0, scale, scale, scale);
                
            // Render overlay cube at actual position and orientation as transparent.
            // This properly achieves occlusion, but I'm not exactly sure why.
            occlusionGLCube.draw(mvpMatrix, Transparency.TRANSPARENT, programID);
            
            // Render wire frame cube overlay
            if(MenuAndParams.cubeOverlayDisplay == true)
            	overlayGLCube.draw(mvpMatrix, programID);
            
            
			// Camera Calibration Diagnostic Test : Continually rotate an arrow through 360 degrees.
			if (MenuAndParams.cameraCalDiagMode == true) {
				renderTestRotationArrow(mvpMatrix, 4 * getRotationInDegrees());
			}

			// Render big Rotate Cube Arrow
			else if (stateModel.appState == AppStateEnum.ROTATE_CUBE) {
				renderCubeFullRotationArrow(mvpMatrix, getRotationInDegrees());
			}

			// Render more slender Edge Rotate Arrow
			else if (stateModel.appState == AppStateEnum.ROTATE_FACE) {
				renderCubeEdgeRotationArrow(mvpMatrix, getRotationInDegrees());
			}	
        }

	    
	    // Render Pilot Cube
        if(MenuAndParams.pilotCubeDisplay == true && stateModel.renderPilotCube == true) {
            
            System.arraycopy(pvMatrix, 0, mvpMatrix, 0, pvMatrix.length);

            // Instead of using pose esitmator coordinates, instead position cube at
            // fix location.  We really just desire to observe rotation.
            Matrix.translateM(mvpMatrix, 0, -6.0f, 0.0f, -15.0f);

            // Rotation Cube per Pose Estimator 
            GLUtil.rotateMatrix(mvpMatrix, poseRotationMatrix);

            // Rotation Cube per additional requests 
            GLUtil.rotateMatrix(mvpMatrix, stateModel.additionalGLCubeRotation);
            
            pilotGLCube.draw(mvpMatrix, Transparency.OPAQUE, programID);
        }
	}


	/**
	 * Compute Pose Rotation Matrix and return it.
	 * 
	 * @param cubePose
	 * @return Pose Rotation Matrix
	 */
	private float[] computePoseRotationMatrix(CubePose cubePose) {
		
		// Rotational matrix suitable for consumption by OpenGL
		float[] poseRotationMatrix = new float[16];
		
		// Recreate Open CV matrix for processing by Rodriques algorithm.
		Mat rvec = new Mat(3, 1, CvType.CV_64FC1);
        rvec.put(0, 0, new double[]{cubePose.xRotation});
        rvec.put(1, 0, new double[]{cubePose.yRotation});
        rvec.put(2, 0, new double[]{cubePose.zRotation});
//		Log.v(Constants.TAG, "Rotation Vector: " + rvec.dump());
        
		// Create an OpenCV Rotation Matrix from a Rotation Vector
		Mat rMatrix = new Mat(4, 4, CvType.CV_64FC1);
		Calib3d.Rodrigues(rvec, rMatrix);
//		Log.v(Constants.TAG, "Rodrigues Matrix: " + rMatrix.dump());


		/*
		 * Create an OpenGL Rotation Matrix
		 * Notes:
		 *   o  OpenGL is in column-row order (correct?).
		 *   o  OpenCV Rodrigues Rotation Matrix is 3x3 where OpenGL Rotation Matrix is 4x4.
		 *   o  OpenGL Rotation Matrix is simple float array variable
		 */

        // Initialize all Rotational Matrix elements to zero.
		for(int i=0; i<16; i++)
		    poseRotationMatrix[i] = 0.0f; // Initialize to zero

		// Initialize element [3,3] to 1.0: i.e., "w" component in homogenous coordinates
        poseRotationMatrix[3*4 + 3] = 1.0f;

        // Copy OpenCV matrix to OpenGL matrix element by element.
        for(int r=0; r<3; r++)
            for(int c=0; c<3; c++)
                poseRotationMatrix[r + c*4] = (float)(rMatrix.get(r, c)[0]);
        
        // Diagnostics
//        for(int r=0; r<4; r++)
//            Log.v(Constants.TAG, String.format("Rotation Matrix  r=%d  [%5.2f  %5.2f  %5.2f  %5.2f]", r, poseRotationMatrix[r + 0], poseRotationMatrix[r+4], poseRotationMatrix[r+8], poseRotationMatrix[r+12]));

        return poseRotationMatrix;
	}

	

	/**
	 * Get Rotation In Degrees
	 * 
	 * Calculate a 0 to 90 degrees rotation for a more pleasing
	 * graphical rotation instructions.
	 * 
	 * Note, this is call from the GL thread on every frame.  Logic is 
	 * added so that return value starts at 0 when app state change
	 * is noticed.
	 * 
	 * @return
	 */
	private int getRotationInDegrees() {

		long time = System.currentTimeMillis();

		// Set member data timeReference when graphic arrow turns on.
		if( (stateModel.appState == AppStateEnum.ROTATE_CUBE) || (stateModel.appState == AppStateEnum.ROTATE_FACE) ){
			if(rotationActive == false) {
				timeReference = time;
				rotationActive = true;
			}
		}
		else
			rotationActive = false;


		// Calculate arrow animation rotation.
		int rate = MenuAndParams.cameraCalDiagMode ? 20 : 10; 
		int arrowRotationInDegrees = (int) (( (time - timeReference) / rate ) % 90);
		return arrowRotationInDegrees;
	}
	private boolean rotationActive = false;
	private long timeReference = 0;;
	
	



	/**
	 * Render Test Rotation Arrow
	 * 
	 * Render an arrow that rotates 360 degrees around cube
	 * 
	 * @param mvpMatrix
	 * @param arrowRotationInDegrees 
	 * @param i
	 */
	private void renderTestRotationArrow(float[] mvpMatrix, int arrowRotationInDegrees) {

		// Rotate test arrow to give impression of movement.
		Matrix.rotateM(mvpMatrix, 0, arrowRotationInDegrees , 0.0f, 1.0f, 0.0f);
		
		// Rotation axis of test arrow is now Y axis.
		Matrix.rotateM(mvpMatrix, 0, 90, 1.0f, 0.0f, 0.0f);

		// Change Arrow Scale: make bigger and narrower
		Matrix.scaleM(mvpMatrix, 0, 2.0f, 2.0f, 0.5f);

		// Render Quarter Turn Arrow
		arrowQuarterTurn.draw(mvpMatrix, ColorTileEnum.WHITE.cvColor, programID);		
	}
    
    /**
	 * Render Cube Edge Rotation Arrow
	 * 
	 * Render an Rubik Cube Edge Rotation request/instruction.
	 * This is used after a solution has been computed and to instruct 
	 * the user to rotate one edge at a time.
	 * 
	 * @param mvpMatrix
     * @param arrowRotationInDegrees 
	 */
	private void renderCubeEdgeRotationArrow(final float[] mvpMatrix, int arrowRotationInDegrees) {
		
		String moveNumonic = stateModel.solutionResultsArray[stateModel.solutionResultIndex];

		Rotation rotation;
		Amount amount;
		
		if(moveNumonic.length() == 1)  {
			rotation = Rotation.CLOCKWISE;
			amount = Amount.QUARTER_TURN;
		}
		else if(moveNumonic.charAt(1) == '2') {
			rotation = Rotation.ONE_HUNDRED_EIGHTY;
			amount = Amount.HALF_TURN;
		}
		else if(moveNumonic.charAt(1) == '\'') {
			rotation = Rotation.COUNTER_CLOCKWISE;
			amount = Amount.QUARTER_TURN;
		}
		else
			throw new java.lang.Error("Unknow rotation amount: problem with ascii format of logic solution");
		
		
		Scalar color = null;
		Direction direction = null;
		
		// Rotate and Translate Arrow as required by Rubik Logic Solution algorithm. 
		switch(moveNumonic.charAt(0)) {
		case 'U':
			color = stateModel.getFaceByName(FaceNameEnum.UP).observedTileArray[1][1].cvColor;
			Matrix.translateM(mvpMatrix, 0, 0.0f, +2.0f, 0.0f);
			Matrix.rotateM(mvpMatrix, 0, 90f, 1.0f, 0.0f, 0.0f);  // X rotation
			direction = (rotation == Rotation.CLOCKWISE) ?         Direction.NEGATIVE : Direction.POSITIVE; 
			break;
		case 'D':
			color = stateModel.getFaceByName(FaceNameEnum.DOWN).observedTileArray[1][1].cvColor;		
			Matrix.translateM(mvpMatrix, 0, 0.0f, -2.0f, 0.0f);
			Matrix.rotateM(mvpMatrix, 0, 90f, 1.0f, 0.0f, 0.0f);  // X rotation
			direction = (rotation == Rotation.COUNTER_CLOCKWISE) ? Direction.NEGATIVE : Direction.POSITIVE; 
			break;
		case 'L':
			color = stateModel.getFaceByName(FaceNameEnum.LEFT).observedTileArray[1][1].cvColor;
			Matrix.translateM(mvpMatrix, 0, -2.0f, 0.0f, 0.0f);
			Matrix.rotateM(mvpMatrix, 0, 90f, 0.0f, 1.0f, 0.0f);  // Y rotation
			direction = (rotation == Rotation.CLOCKWISE) ?         Direction.NEGATIVE : Direction.POSITIVE; 
			Matrix.rotateM(mvpMatrix, 0, 30f, 0.0f, 0.0f, 1.0f);  // looks better
			break;
		case 'R':
			color = stateModel.getFaceByName(FaceNameEnum.RIGHT).observedTileArray[1][1].cvColor;
			Matrix.translateM(mvpMatrix, 0, +2.0f, 0.0f, 0.0f);
			Matrix.rotateM(mvpMatrix, 0, 90f, 0.0f, 1.0f, 0.0f);  // Y rotation
			direction = (rotation == Rotation.COUNTER_CLOCKWISE) ? Direction.NEGATIVE : Direction.POSITIVE;
			Matrix.rotateM(mvpMatrix, 0, 30f, 0.0f, 0.0f, 1.0f);  // looks better
			break;
		case 'F':
			color = stateModel.getFaceByName(FaceNameEnum.FRONT).observedTileArray[1][1].cvColor;
			Matrix.translateM(mvpMatrix, 0, 0.0f, 0.0f, +2.0f);
			direction = (rotation == Rotation.COUNTER_CLOCKWISE) ? Direction.NEGATIVE : Direction.POSITIVE; 
			Matrix.rotateM(mvpMatrix, 0, 30f, 0.0f, 0.0f, 1.0f);  // looks better
			break;
		case 'B':
			color = stateModel.getFaceByName(FaceNameEnum.BACK).observedTileArray[1][1].cvColor;
			Matrix.translateM(mvpMatrix, 0, 0.0f, 0.0f, -2.0f);
			direction = (rotation == Rotation.CLOCKWISE) ?         Direction.NEGATIVE : Direction.POSITIVE; 
			Matrix.rotateM(mvpMatrix, 0, 30f, 0.0f, 0.0f, 1.0f);  // looks better
			break;
		}
		
	      
		// Add animated rotation and specify direction of arrow
		if(direction == Direction.NEGATIVE)  {
		    Matrix.rotateM(mvpMatrix, 0, arrowRotationInDegrees, 0.0f, 0.0f, 1.0f);  // 0 -> +90 degrees Z rotation

			Matrix.rotateM(mvpMatrix, 0, -90f,  0.0f, 0.0f, 1.0f);  // Z rotation of -90
			Matrix.rotateM(mvpMatrix, 0, +180f, 0.0f, 1.0f, 0.0f);  // Y rotation of +180
		}

		else
	         Matrix.rotateM(mvpMatrix, 0, -1 * arrowRotationInDegrees, 0.0f, 0.0f, 1.0f);  // 0 -> -90 degrees Z rotation
		
		// Set radius to 1.5 units.
		Matrix.scaleM(mvpMatrix, 0, 1.5f, 1.5f, 1.0f);
		
 
		if(amount == Amount.QUARTER_TURN)
			arrowQuarterTurn.draw(mvpMatrix, color, programID);
		else
			arrowHalfTurn.draw(mvpMatrix, color, programID);
	}
	


	/**
	 * Render Cube Full Rotation Arrow
	 * 
	 * Render a Rubik Cube Body Rotation request/instruction.
	 * This is used during the exploration phase to observed all six
	 * sides of the cube before any solution is compute or attempted.
	 * 
	 * @param mvpMatrix
	 * @param arrowRotationInDegrees 
	 */
	private void renderCubeFullRotationArrow(final float[] mvpMatrix, int arrowRotationInDegrees) {
	            
		// Render arrow front to top, or right side to top.
		if(stateModel.getNumObservedFaces() % 2 == 0) {
            Matrix.rotateM(mvpMatrix, 0, -90f, 0.0f, 1.0f, 0.0f);  // Y rotation of -90
		}

		// Roate arrow to give impression of movement.  Also, start back at -60 degrees: looks better.
        Matrix.rotateM(mvpMatrix, 0, arrowRotationInDegrees - 60, 0.0f, 0.0f, 1.0f);  // -60 -> +30 degrees Z rotation

		// Reverse direction of arrow.
        Matrix.rotateM(mvpMatrix, 0, -90f,  0.0f, 0.0f, 1.0f);  // Z rotation of -90
        Matrix.rotateM(mvpMatrix, 0, +180f, 0.0f, 1.0f, 0.0f);  // Y rotation of +180

		// Make Arrow Wider than normal by a factor of three and also with a 2 unit radius.
        Matrix.scaleM(mvpMatrix, 0, 2.0f, 2.0f, 3.0f);
		
		// Render Quarter Turn Arrow
		arrowQuarterTurn.draw(mvpMatrix, ColorTileEnum.WHITE.cvColor, programID);
	}

}
