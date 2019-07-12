/*******************************************************************************
 * Copyright (c) 2015 - 2017
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.graphics.map.draw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

import go.graphics.AdvancedUpdateBufferCache;
import go.graphics.EBufferFormatType;
import go.graphics.GL2DrawContext;
import go.graphics.GLDrawContext;
import go.graphics.BufferHandle;
import go.graphics.IllegalBufferException;
import go.graphics.TextureHandle;

import jsettlers.common.CommonConstants;
import jsettlers.common.landscape.ELandscapeType;
import jsettlers.common.map.IDirectGridProvider;
import jsettlers.common.map.IGraphicsBackgroundListener;
import jsettlers.common.map.shapes.MapRectangle;
import jsettlers.common.position.FloatRectangle;
import jsettlers.graphics.map.MapDrawContext;
import jsettlers.graphics.image.reader.DatBitmapReader;
import jsettlers.graphics.image.reader.DatFileReader;
import jsettlers.graphics.image.reader.ImageArrayProvider;
import jsettlers.graphics.image.reader.ImageMetadata;
import go.graphics.UpdateBufferCache;

/**
 * The map background.
 * <p>
 * This class draws the map background (landscape) layer. It has support for smooth FOW transitions and buffers the background to make it faster.
 *
 * @author Michael Zangl
 */
public class Background implements IGraphicsBackgroundListener {

	private static final int LAND_FILE = 0;

	/**
	 * The base texture size.
	 */
	private static final int TEXTURE_SIZE = 1024;

	/**
	 * Our base texture is divided into multiple squares that all hold a single texture. Continuous textures occupy 5*5 squares
	 */
	private static final int TEXTURE_GRID = 32;

	private static final int BYTES_PER_FIELD_SHAPE = 4*5*3*2; // 4 bytes per float * 5 components(x,y,z,t,v) * 3 points per triangle * 2 triangles per field
	private static final int BYTES_PER_FIELD_COLOR = 4*3*2; // 4 component(r,g,b,a actually gray) * 3 points per triangle * 2 triangles per field

	/**
	 * Where are the textures on the map?
	 * <p>
	 * x and y coordinates are in Grid units.
	 * <p>
	 * The third entry is the size of the texture. It must be 1 for border tiles and 2..5 for continuous images. Always 1 more than they are wide.
	 *
	 * <pre>
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |0             |1             |3             |4             |5             |7             | 5| 6|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             | 8| 9|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |11|12|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |13|14|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |15|16|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |10            |18            |              |              |              |              |17|19|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |20|22|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |     21       |     24       |     31       |      35      |23|25|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |26|27|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |28|29|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |              |              |              |              |              |              |30|32|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |33|34|
	 * +              +              +              +              +              +              +--+--+
	 * |      36      |     176      |              |              |              |              |98|99|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |37|38|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |39|40|
	 * |41|42|43|44|45|46|47|48|49|50|51|52|53|54|55|56|57|58|59|60|61|62|63|64|65|66|67|68|69|70|71|72|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+ --- ↓ +100
	 * |73|74|75|76|77|78|79|80|81|82|83|84|85|86|87|88|89|90|91|92|93|94|95|96|97|00|01|02|03|04|05|06|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |07|08|09|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|30|31|32|33|34|35|36|37|38|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |39|40|41|42|43|44|45|46|47|48|49|50|51|52|53|54|55|56|57|58|59|60|61|62|63|64|65|66|67|68|69|70|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+ --- ↓ +200
	 * |71|72|73|74|75|  |77|78|79|80|81|82|83|84|85|86|87|88|89|90|91|92|93|94|95|96|97|98|99|00|01|02|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |     |     |     |     |     |     |     |              |              |
	 * | 011 | 012 | 013 | 014 | 015 | 016 | 017 |              |              |
	 * |     |     |     |     |     |     |     |              |     230      |
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+  217         |              |--+--+--+--+--+--+--+--+
	 * +03|04|05|06|07|08|09|10|11|12|13|14|15|16|              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+              |              |--+--+--+--+--+--+--+--+
	 * +18|19|20|21|22|23|24|25|26|27|28|29|31|32|              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+              |              |--+--+--+--+--+--+--+--+
	 * +33|34|                                   |              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 */
	private static final int[][] TEXTURE_POSITIONS = {
			/* 0: big */{
					0, 0, 5
			},
			/* 1: big */{
					5, 0, 5
			},
			/* 2: big */{
					10, 0, 5
			},
			/* 3: big */{
					15, 0, 5
			},
			/* 4: big */{
					20, 0, 5
			},
			/* 5: small */{
					30, 0, 1
			},
			/* 6: small */{
					31, 0, 1
			},
			/* 7: big */{
					25, 0, 5
			},
			/* 8: small */{
					30, 1, 1
			},
			/* 9: small */{
					31, 1, 1
			},
			/* 10: big */{
					0, 5, 5
			},
			/* 11: small, continuous */{
					0, 20, 2
			},
			/* 12: small, continuous */{
					2, 20, 2
			},
			/* 13: small, continuous */{
					4, 20, 2
			},
			/* 14: small, continuous */{
					6, 20, 2
			},
			/* 15: small, continuous */{
					8, 20, 2
			},
			/* 16: small, continuous */{
					10, 20, 2
			},
			/* 17: small, continuous */{
					12, 20, 2
			},
			/* 18: big */{
					5, 5, 5
			},
			/* 19: small */{
					31, 5, 1
			},
			/* 20: small */{
					30, 6, 1
			},
			/* 21: big */{
					10, 5, 5
			},
			/* 22: small */{
					31, 6, 1
			},
			/* 23: small */{
					30, 7, 1
			},
			/* 24: big */{
					15, 5, 5
			},
			/* 25: small */{
					31, 7, 1
			},
			/* 26: small */{
					30, 8, 1
			},
			/* 27: small */{
					31, 8, 1
			},
			/* 28: small */{
					30, 9, 1
			},
			/* 29: small */{
					31, 9, 1
			},
			/* 30: small */{
					30, 10, 1
			},
			/* 31: big */{
					20, 5, 5
			},
			/* 32: small */{
					31, 10, 1
			},
			/* 33: small */{
					30, 11, 1
			},
			/* 34: small */{
					31, 11, 1
			},
			/* 35: big */{
					25, 5, 5
			},
			/* 36: big */{
					0, 10, 5
			},
			/* 37: small */{
					30, 13, 1
			},
			/* 38: small */{
					31, 13, 1
			},
			/* 39: small */{
					30, 14, 1
			},
			/* 40: small */{
					31, 14, 1
			},
			/* 41: small */{
					0, 15, 1
			},
			/* 42: small */{
					1, 15, 1
			},
			/* 43: small */{
					2, 15, 1
			},
			/* 44: small */{
					3, 15, 1
			},
			/* 45: small */{
					4, 15, 1
			},
			/* 46: small */{
					5, 15, 1
			},
			/* 47: small */{
					6, 15, 1
			},
			/* 48: small */{
					7, 15, 1
			},
			/* 49: small */{
					8, 15, 1
			},
			/* 50: small */{
					9, 15, 1
			},
			/* 51: small */{
					10, 15, 1
			},
			/* 52: small */{
					11, 15, 1
			},
			/* 53: small */{
					12, 15, 1
			},
			/* 54: small */{
					13, 15, 1
			},
			/* 55: small */{
					14, 15, 1
			},
			/* 56: small */{
					15, 15, 1
			},
			/* 57: small */{
					16, 15, 1
			},
			/* 58: small */{
					17, 15, 1
			},
			/* 59: small */{
					18, 15, 1
			},
			/* 60: small */{
					19, 15, 1
			},
			/* 61: small */{
					20, 15, 1
			},
			/* 62: small */{
					21, 15, 1
			},
			/* 63: small */{
					22, 15, 1
			},
			/* 64: small */{
					23, 15, 1
			},
			/* 65: small */{
					24, 15, 1
			},
			/* 66: small */{
					25, 15, 1
			},
			/* 67: small */{
					26, 15, 1
			},
			/* 68: small */{
					27, 15, 1
			},
			/* 69: small */{
					28, 15, 1
			},
			/* 70: small */{
					29, 15, 1
			},
			/* 71: small */{
					30, 15, 1
			},
			/* 72: small */{
					31, 15, 1
			},
			// ------------------------------------
			/* 73: small */{
					0, 16, 1
			},
			/* 74: small */{
					1, 16, 1
			},
			/* 75: small */{
					2, 16, 1
			},
			/* 76: small */{
					3, 16, 1
			},
			/* 77: small */{
					4, 16, 1
			},
			/* 78: small */{
					5, 16, 1
			},
			/* 79: small */{
					6, 16, 1
			},
			/* 80: small */{
					7, 16, 1
			},
			/* 81: small */{
					8, 16, 1
			},
			/* 82: small */{
					9, 16, 1
			},
			/* 83: small */{
					10, 16, 1
			},
			/* 84: small */{
					11, 16, 1
			},
			/* 85: small */{
					12, 16, 1
			},
			/* 86: small */{
					13, 16, 1
			},
			/* 87: small */{
					14, 16, 1
			},
			/* 88: small */{
					15, 16, 1
			},
			/* 89: small */{
					16, 16, 1
			},
			/* 90: small */{
					17, 16, 1
			},
			/* 91: small */{
					18, 16, 1
			},
			/* 92: small */{
					19, 16, 1
			},
			/* 93: small */{
					20, 16, 1
			},
			/* 94: small */{
					21, 16, 1
			},
			/* 95: small */{
					22, 16, 1
			},
			/* 96: small */{
					23, 16, 1
			},
			/* 97: small */{
					24, 16, 1
			},
			/* 98: small */{
					30, 16, 1
			},
			/* 99: small */{
					31, 12, 1
			},
			/* 100: small */{
					25, 12, 1
			},
			/* 101: small */{
					26, 16, 1
			},
			/* 102: small */{
					27, 16, 1
			},
			/* 103: small */{
					28, 16, 1
			},
			/* 104: small */{
					29, 16, 1
			},
			/* 105: small */{
					30, 16, 1
			},
			/* 106: small */{
					31, 16, 1
			},
			// ------------------------------------
			/* 107: small */{
					0, 17, 1
			},
			/* 108: small */{
					1, 17, 1
			},
			/* 109: small */{
					2, 17, 1
			},
			/* 110: small */{
					3, 17, 1
			},
			/* 111: small */{
					4, 17, 1
			},
			/* 112: small */{
					5, 17, 1
			},
			/* 113: small */{
					6, 17, 1
			},
			/* 114: small */{
					7, 17, 1
			},
			/* 115: small */{
					8, 17, 1
			},
			/* 116: small */{
					9, 17, 1
			},
			/* 117: small */{
					10, 17, 1
			},
			/* 118: small */{
					11, 17, 1
			},
			/* 119: small */{
					12, 17, 1
			},
			/* 120: small */{
					13, 17, 1
			},
			/* 121: small */{
					14, 17, 1
			},
			/* 122: small */{
					15, 17, 1
			},
			/* 123: small */{
					16, 17, 1
			},
			/* 124: small */{
					17, 17, 1
			},
			/* 125: small */{
					18, 17, 1
			},
			/* 126: small */{
					19, 17, 1
			},
			/* 127: small */{
					20, 17, 1
			},
			/* 128: small */{
					21, 17, 1
			},
			/* 129: small */{
					22, 17, 1
			},
			/* 130: small */{
					23, 17, 1
			},
			/* 131: small */{
					24, 17, 1
			},
			/* 132: small */{
					25, 17, 1
			},
			/* 133: small */{
					26, 17, 1
			},
			/* 134: small */{
					27, 17, 1
			},
			/* 135: small */{
					28, 17, 1
			},
			/* 136: small */{
					29, 17, 1
			},
			/* 137: small */{
					30, 17, 1
			},
			/* 138: small */{
					31, 17, 1
			},
			// ------------------------------------
			/* 139: small */{
					0, 18, 1
			},
			/* 140: small */{
					1, 18, 1
			},
			/* 141: small */{
					2, 18, 1
			},
			/* 142: small */{
					3, 18, 1
			},
			/* 143: small */{
					4, 18, 1
			},
			/* 144: small */{
					5, 18, 1
			},
			/* 145: small */{
					6, 18, 1
			},
			/* 146: small */{
					7, 18, 1
			},
			/* 147: small */{
					8, 18, 1
			},
			/* 148: small */{
					9, 18, 1
			},
			/* 149: small */{
					10, 18, 1
			},
			/* 150: small */{
					11, 18, 1
			},
			/* 151: small */{
					12, 18, 1
			},
			/* 152: small */{
					13, 18, 1
			},
			/* 153: small */{
					14, 18, 1
			},
			/* 154: small */{
					15, 18, 1
			},
			/* 155: small */{
					16, 18, 1
			},
			/* 156: small */{
					17, 18, 1
			},
			/* 157: small */{
					18, 18, 1
			},
			/* 158: small */{
					19, 18, 1
			},
			/* 159: small */{
					20, 18, 1
			},
			/* 160: small */{
					21, 18, 1
			},
			/* 161: small */{
					22, 18, 1
			},
			/* 162: small */{
					23, 18, 1
			},
			/* 163: small */{
					24, 18, 1
			},
			/* 164: small */{
					25, 18, 1
			},
			/* 165: small */{
					26, 18, 1
			},
			/* 166: small */{
					27, 18, 1
			},
			/* 167: small */{
					28, 18, 1
			},
			/* 168: small */{
					29, 18, 1
			},
			/* 169: small */{
					30, 18, 1
			},
			/* 170: small */{
					31, 18, 1
			},
			// ------------------------------------
			/* 171: small */{
					0, 19, 1
			},
			/* 172: small */{
					1, 19, 1
			},
			/* 173: small */{
					2, 19, 1
			},
			/* 174: small */{
					3, 19, 1
			},
			/* 175: small */{
					4, 19, 1
			},
			/* 176: big (odd shape?) */{
					5, 10, 5
			},
			/* 177: small */{
					6, 19, 1
			},
			/* 178: small */{
					7, 19, 1
			},
			/* 179: small */{
					8, 19, 1
			},
			/* 180: small */{
					9, 19, 1
			},
			/* 181: small */{
					10, 19, 1
			},
			/* 182: small */{
					11, 19, 1
			},
			/* 183: small */{
					12, 19, 1
			},
			/* 184: small */{
					13, 19, 1
			},
			/* 185: small */{
					14, 19, 1
			},
			/* 186: small */{
					15, 19, 1
			},
			/* 187: small */{
					16, 19, 1
			},
			/* 188: small */{
					17, 19, 1
			},
			/* 189: small */{
					18, 19, 1
			},
			/* 190: small */{
					19, 19, 1
			},
			/* 191: small */{
					20, 19, 1
			},
			/* 192: small */{
					21, 19, 1
			},
			/* 193: small */{
					22, 19, 1
			},
			/* 194: small */{
					23, 19, 1
			},
			/* 195: small */{
					24, 19, 1
			},
			/* 196: small */{
					25, 19, 1
			},
			/* 197: small */{
					26, 19, 1
			},
			/* 198: small */{
					27, 19, 1
			},
			/* 199: small */{
					28, 19, 1
			},
			/* 200: small */{
					29, 19, 1
			},
			/* 201: small */{
					30, 19, 1
			},
			/* 202: small */{
					31, 19, 1
			},
			// ------------------------------------

			/* 203: small */{
					0, 22, 1
			},
			/* 204: small */{
					1, 22, 1
			},
			/* 205: small */{
					2, 22, 1
			},
			/* 206: small */{
					3, 22, 1
			},
			/* 207: small */{
					4, 22, 1
			},
			/* 208: small */{
					5, 22, 1
			},
			/* 209: small */{
					6, 22, 1
			},
			/* 210: small */{
					7, 22, 1
			},
			/* 211: small */{
					8, 22, 1
			},
			/* 212: small */{
					9, 22, 1
			},

			/* 213: small */{
					10, 22, 1
			},
			/* 214: small */{
					11, 22, 1
			},
			/* 215: small */{
					12, 22, 1
			},
			/* 216: small */{
					13, 22, 1
			},
			/* 217: big */{
					14, 20, 5
			},
			/* 218: small */{
					1, 23, 1
			},
			/* 219: small */{
					2, 23, 1
			},
			/* 220: small */{
					3, 23, 1
			},
			/* 221: small */{
					4, 23, 1
			},
			/* 222: small */{
					5, 23, 1
			},

			/* 223: small */{
					6, 23, 1
			},
			/* 224: small */{
					7, 23, 1
			},
			/* 225: small */{
					8, 23, 1
			},
			/* 226: small */{
					9, 23, 1
			},
			/* 227: small */{
					10, 23, 1
			},
			/* 228: small */{
					11, 23, 1
			},
			/* 229: small */{
					12, 23, 1
			},
			/* 230: big */{
					19, 20, 5
			},
			/* 231: small */{
					13, 23, 1
			},
			/* 232: small */{
					0, 24, 1
			},
			/* 233: small */{
					1, 24, 1
			},
			/* 234: small */{
					2, 24, 1
			},

			// ...
	};

	private static boolean asyncBufferBuilding = true;

	private static final Object preloadMutex = new Object();

	private int bufferWidth; // in map points.
	private int bufferHeight; // in map points.

	private static TextureHandle texture = null;

	private BufferHandle shapeHandle = null;
	private BufferHandle colorHandle = null;

	private boolean hasdgp;
	private IDirectGridProvider dgp;
	private byte[][] dgpVisibleStatus;
	private byte[] dgpHeightGrid;
	private BitSet fowWritten;
	private boolean fowEnabled;

	private boolean useFloatColors;
	private boolean updateGeometry = false;
	private BitSet mapInvalid;
	private int mapWidth, mapHeight;

	private static short[] preloadedTexture = null;

	private static short[] getTexture() {
		short[] data = new short[TEXTURE_SIZE * TEXTURE_SIZE];
		try {
			addTextures(data);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return data;
	}

	static void preloadTexture() {
		synchronized (preloadMutex) {
			if (preloadedTexture == null) {
				preloadedTexture = getTexture();
				ImageProvider.getInstance().addPreloadTask(Background::getTexture);
			}
		}
	}

	private static TextureHandle getTexture(GLDrawContext context) {
		if (texture == null || !texture.isValid()) {
			long startTime = System.currentTimeMillis();
			short[] data;
			synchronized (preloadMutex) {
				if (preloadedTexture != null) {
					data = preloadedTexture;
					// free the array
					preloadedTexture = null;
				} else {
					data = getTexture();
				}
			}
			ByteBuffer buffer = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder());
			buffer.asShortBuffer().put(data);
			texture = context.generateTexture(TEXTURE_SIZE, TEXTURE_SIZE, buffer.asShortBuffer(), "background");

			System.out.println("Background texture generated in " + (System.currentTimeMillis() - startTime) + "ms");
		}
		return texture;
	}

	private static class ImageWriter implements ImageArrayProvider {
		int arrayOffset;
		int cellSize;
		int maxOffset;
		short[] data;

		// nothing to do. We assume images are a rectangle and have the right size.
		@Override
		public void startImage(int width, int height) {
		}

		@Override
		public void writeLine(short[] data, int length) {
			if (arrayOffset < maxOffset) {
				for (int i = 0; i < cellSize; i++) {
					this.data[arrayOffset + i] = data[i % length];
				}
				arrayOffset += TEXTURE_SIZE;
			}
		}
	}

	/**
	 * Generates the texture data.
	 *
	 * @param data
	 *            The texture data buffer.
	 * @throws IOException
	 *            If the necessary file reader is missing
	 */
	private static void addTextures(short[] data) throws IOException {
		DatFileReader reader = ImageProvider.getInstance().getFileReader(LAND_FILE);
		if (reader == null) {
			throw new IOException("Could not get a file reader for the file.");
		}
		ImageWriter imageWriter = new ImageWriter();
		imageWriter.data = data;

		ImageMetadata meta = new ImageMetadata();

		for (int index = 0; index < TEXTURE_POSITIONS.length; index++) {
			int[] position = TEXTURE_POSITIONS[index];
			int x = position[0] * TEXTURE_GRID;
			int y = position[1] * TEXTURE_GRID;
			int start = y * TEXTURE_SIZE + x;
			int cellSize = position[2] * TEXTURE_GRID;
			int end = (y + cellSize) * TEXTURE_SIZE + x;
			imageWriter.arrayOffset = start;
			imageWriter.cellSize = cellSize;
			imageWriter.maxOffset = end;

			DatBitmapReader.uncompressImage(reader.getReaderForLandscape(index), reader.getLandscapeTranslator(), meta, imageWriter);

			// freaky stuff
			int arrayOffset = imageWriter.arrayOffset;
			int l = arrayOffset - start;
			while (arrayOffset < end) {
				System.arraycopy(data, arrayOffset - l, data, arrayOffset, cellSize);
				arrayOffset += TEXTURE_SIZE;
			}
		}
	}

	/**
	 * Gets the image number of the border
	 * 
	 * @param outer
	 *            The outer landscape (that has two triangle edges).
	 * @param inner
	 *            The inner landscape.
	 * @param useSecond
	 *            If it is true, the secondary texture is used.
	 * @return The texture.
	 */
	private static int getBorder(ELandscapeType outer, ELandscapeType inner, boolean useSecond) {
		int index;

		// water <=> water
		if (outer == ELandscapeType.WATER1 && inner == ELandscapeType.WATER2) {
			index = 84;
		} else if (outer == ELandscapeType.WATER2 && inner == ELandscapeType.WATER1) {
			index = 86;

		} else if (outer == ELandscapeType.WATER2 && inner == ELandscapeType.WATER3) {
			index = 88;
		} else if (outer == ELandscapeType.WATER3 && inner == ELandscapeType.WATER2) {
			index = 90;

		} else if (outer == ELandscapeType.WATER3 && inner == ELandscapeType.WATER4) {
			index = 92;
		} else if (outer == ELandscapeType.WATER4 && inner == ELandscapeType.WATER3) {
			index = 94;

		} else if (outer == ELandscapeType.WATER4 && inner == ELandscapeType.WATER5) {
			index = 96;
		} else if (outer == ELandscapeType.WATER5 && inner == ELandscapeType.WATER4) {
			index = 98;

		} else if (outer == ELandscapeType.WATER5 && inner == ELandscapeType.WATER6) {
			index = 100;
		} else if (outer == ELandscapeType.WATER6 && inner == ELandscapeType.WATER5) {
			index = 102;

		} else if (outer == ELandscapeType.WATER6 && inner == ELandscapeType.WATER7) {
			index = 104;
		} else if (outer == ELandscapeType.WATER7 && inner == ELandscapeType.WATER6) {
			index = 106;

		} else if (outer == ELandscapeType.WATER7 && inner == ELandscapeType.WATER8) {
			index = 108;
		} else if (outer == ELandscapeType.WATER8 && inner == ELandscapeType.WATER7) {
			index = 110;

			// grass <=> dessert
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.DESERT) {
			index = 181;
		} else if (outer == ELandscapeType.DESERT && inner == ELandscapeType.GRASS) {
			index = 183;

			// water <=> sand
		} else if (outer == ELandscapeType.WATER1 && inner == ELandscapeType.SAND) {
			index = 39;
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.WATER1) {
			index = 37;

			// grass <=> mountain
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.MOUNTAINBORDEROUTER) {
			index = 116;
		} else if (outer == ELandscapeType.MOUNTAINBORDEROUTER && inner == ELandscapeType.GRASS) {
			index = 118;

		} else if (outer == ELandscapeType.MOUNTAINBORDEROUTER && inner == ELandscapeType.MOUNTAINBORDER) {
			index = 120;
		} else if (outer == ELandscapeType.MOUNTAINBORDER && inner == ELandscapeType.MOUNTAINBORDEROUTER) {
			index = 122;

		} else if (outer == ELandscapeType.MOUNTAINBORDER && inner == ELandscapeType.MOUNTAIN) {
			index = 124;
		} else if (outer == ELandscapeType.MOUNTAIN && inner == ELandscapeType.MOUNTAINBORDER) {
			index = 126;

			// mountain <=> snow
		} else if (outer == ELandscapeType.MOUNTAIN && inner == ELandscapeType.SNOW) {
			index = 156; // OLD!
		} else if (outer == ELandscapeType.SNOW && inner == ELandscapeType.MOUNTAIN) {
			index = 158; // OLD!
		} else if (outer == ELandscapeType.MOUNTAIN && inner == ELandscapeType.SNOWBORDER) {
			index = 156;
		} else if (outer == ELandscapeType.SNOWBORDER && inner == ELandscapeType.MOUNTAIN) {
			index = 158;
		} else if (outer == ELandscapeType.SNOWBORDER && inner == ELandscapeType.SNOW) {
			index = 160;
		} else if (outer == ELandscapeType.SNOW && inner == ELandscapeType.SNOWBORDER) {
			index = 162;

			// earth <=> grass
		} else if (outer == ELandscapeType.EARTH && inner == ELandscapeType.GRASS) {
			index = 170;
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.EARTH) {
			index = 168;

			// grass <=> dry grass
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.DRY_GRASS) {
			index = 116;
		} else if (outer == ELandscapeType.DRY_GRASS && inner == ELandscapeType.GRASS) {
			index = 118;

			// dry grass <=> desert
		} else if (outer == ELandscapeType.DRY_GRASS && inner == ELandscapeType.DESERT) {
			index = 136;
		} else if (outer == ELandscapeType.DESERT && inner == ELandscapeType.DRY_GRASS) {
			index = 138;

			// river <=> grass
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.RIVER1) {
			index = 52;
		} else if (outer == ELandscapeType.RIVER1 && inner == ELandscapeType.GRASS) {
			index = 54;
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.RIVER2) {
			index = 56;
		} else if (outer == ELandscapeType.RIVER2 && inner == ELandscapeType.GRASS) {
			index = 58;
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.RIVER3) {
			index = 60;
		} else if (outer == ELandscapeType.RIVER3 && inner == ELandscapeType.GRASS) {
			index = 62;
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.RIVER4) {
			index = 64;
		} else if (outer == ELandscapeType.RIVER4 && inner == ELandscapeType.GRASS) {
			index = 66;

			// sand <=> river
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.RIVER1) {
			index = 68;
		} else if (outer == ELandscapeType.RIVER1 && inner == ELandscapeType.SAND) {
			index = 70;
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.RIVER2) {
			index = 72;
		} else if (outer == ELandscapeType.RIVER2 && inner == ELandscapeType.SAND) {
			index = 74;
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.RIVER3) {
			index = 76;
		} else if (outer == ELandscapeType.RIVER3 && inner == ELandscapeType.SAND) {
			index = 78;
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.RIVER4) {
			index = 80;
		} else if (outer == ELandscapeType.RIVER4 && inner == ELandscapeType.SAND) {
			index = 82;

			// grass <=> sand
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.SAND) {
			index = 114;
		} else if (outer == ELandscapeType.SAND && inner == ELandscapeType.GRASS) {
			index = 112;

			// grass <=> flattened
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.FLATTENED) {
			index = 172;
		} else if (outer == ELandscapeType.FLATTENED && inner == ELandscapeType.GRASS) {
			index = 174;

			// moor <=> grass
		} else if (outer == ELandscapeType.GRASS && inner == ELandscapeType.MOORBORDER) {
			index = 201;
		} else if (outer == ELandscapeType.MOORBORDER && inner == ELandscapeType.GRASS) {
			index = 203;

		} else if (outer == ELandscapeType.MOORBORDER && inner == ELandscapeType.MOORINNER) {
			index = 205;
		} else if (outer == ELandscapeType.MOORINNER && inner == ELandscapeType.MOORBORDER) {
			index = 207;

		} else if (outer == ELandscapeType.MOORINNER && inner == ELandscapeType.MOOR) {
			index = 209;
		} else if (outer == ELandscapeType.MOOR && inner == ELandscapeType.MOORINNER) {
			index = 211;

			// flattened desert <=> desert

		} else if (outer == ELandscapeType.DESERT && inner == ELandscapeType.SHARP_FLATTENED_DESERT) {
			index = 218;
		} else if (outer == ELandscapeType.SHARP_FLATTENED_DESERT && inner == ELandscapeType.DESERT) {
			index = 220;
		} else if (outer == ELandscapeType.DESERT && inner == ELandscapeType.FLATTENED_DESERT) {
			index = 222;
		} else if (outer == ELandscapeType.FLATTENED_DESERT && inner == ELandscapeType.DESERT) {
			index = 224;

		} else if (outer == ELandscapeType.GRAVEL && inner == ELandscapeType.MOUNTAINBORDER) {
			index = 231;
		} else if (outer == ELandscapeType.MOUNTAINBORDER && inner == ELandscapeType.GRAVEL) {
			index = 233;

		} else {
			index = outer.getImageNumber();
			useSecond = false; // force!
		}

		if (useSecond) {
			index += 1;
		}

		return index;
	}

	public Background(MapDrawContext context) {
		bufferWidth = context.getMap().getWidth()-1;
		bufferHeight = context.getMap().getHeight()-1;
		mapWidth = context.getMap().getWidth();
		mapHeight = context.getMap().getHeight();

		dgp = context.getFow();
		hasdgp = dgp != null;
		if(hasdgp) {
			dgpVisibleStatus = dgp.getVisibleStatusArray();
			dgpHeightGrid = dgp.getHeightArray();
		}

		if(asyncBufferBuilding) {
			color_bfr2 = ByteBuffer.allocateDirect(BYTES_PER_FIELD_COLOR*bufferHeight*bufferWidth).order(ByteOrder.nativeOrder());
			color_cache2 = new AdvancedUpdateBufferCache(color_bfr2, BYTES_PER_FIELD_COLOR, context::getGl, () -> colorHandle, bufferWidth);
			asyncAccessContext = context;
		} else {
			color_bfr2 = null;
			fowWritten = new BitSet(mapWidth*mapHeight);
		}
		mapInvalid = new BitSet(bufferWidth*bufferHeight);
		draw_stride = (2*bufferWidth)+1;
	}

	private int draw_stride;

	private MapDrawContext asyncAccessContext;

	/**
	 * Draws a given map content.
	 *
	 * @param context
	 *            The context to draw at.
	 * @param screen
	 *            The area to draw
	 */
	public void drawMapContent(MapDrawContext context, FloatRectangle screen) {
		try {
			GLDrawContext gl = context.getGl();
			if(shapeHandle == null || !shapeHandle.isValid()) {
				useFloatColors = (gl instanceof GL2DrawContext);
				generateGeometry(context);
				context.getGl().setHeightMatrix(context.getConverter().getMatrixWithHeight());
			}

			MapRectangle screenArea = context.getConverter().getMapForScreen(screen);
			int offset = screenArea.getMinY() * bufferWidth + screenArea.getMinX();

			updateGeometry(context, screenArea);

			gl.drawTrianglesWithTextureColored(getTexture(context.getGl()), shapeHandle, colorHandle, offset * 2, screenArea.getHeight(), screenArea.getWidth() * 2, draw_stride);
		} catch (IllegalBufferException e) {
			// TODO: Create crash report.
			e.printStackTrace();
		}
	}

	private void generateGeometry(MapDrawContext context) throws IllegalBufferException {
		int vertices = bufferWidth*bufferHeight*3*2;
		shapeHandle = context.getGl().generateBuffer(vertices, EBufferFormatType.Texture3D, false, "background-shape");
		colorHandle = context.getGl().generateBuffer(vertices, EBufferFormatType.ColorOnly, true, "background-color");

		shape_bfr = ByteBuffer.allocateDirect(BYTES_PER_FIELD_SHAPE*bufferWidth).order(ByteOrder.nativeOrder());
		color_bfr = ByteBuffer.allocateDirect(BYTES_PER_FIELD_COLOR*bufferWidth).order(ByteOrder.nativeOrder());

		for(int y = 0;y != bufferHeight;y++) {
			for(int x = 0; x != bufferWidth;x++) {
				addTrianglesToGeometry(context, shape_bfr, x, y);
			}
			shape_bfr.rewind();
			context.getGl().updateBufferAt(shapeHandle, BYTES_PER_FIELD_SHAPE*bufferWidth*y, shape_bfr);
		}
		fowEnabled = hasdgp && dgp.isFoWEnabled();

		for(int y = 0; y != bufferHeight; y++) {
			for(int x = 0; x != bufferWidth; x++) {
				addColorTrianglesToGeometry(context, color_bfr, x, y);
			}
			color_bfr.rewind();
			context.getGl().updateBufferAt(colorHandle, BYTES_PER_FIELD_COLOR * bufferWidth * y, color_bfr);
		}

		shape_bfr = ByteBuffer.allocateDirect(BYTES_PER_FIELD_SHAPE).order(ByteOrder.nativeOrder());

		if (!asyncBufferBuilding) {
			color_cache = new UpdateBufferCache(color_bfr, BYTES_PER_FIELD_COLOR, context::getGl, () -> colorHandle);
		}
		context.getMap().setBackgroundListener(this);
	}

	private AdvancedUpdateBufferCache color_cache2;
	private final ByteBuffer color_bfr2;
	private UpdateBufferCache color_cache;
	private ByteBuffer shape_bfr;
	private ByteBuffer color_bfr;

	private void updateGeometry(MapDrawContext context, MapRectangle screen) {
		fowEnabled = hasdgp && dgp.isFoWEnabled();
		try {
			int height = screen.getHeight();
			int width = screen.getWidth();
			int miny = screen.getMinY();
			int minx = screen.getMinX();
			int maxy = miny+height;

			if(maxy > bufferHeight) maxy = bufferHeight;
			if(miny < 0) miny = 0;

			int linestart = minx-(miny/2);
			for (int y = miny; y < maxy; y++) {
				int lineStartX = linestart + (y / 2);

				int linewidth = (width + lineStartX) < bufferWidth ? width + lineStartX : bufferWidth;
				int linex = lineStartX < 0 ? 0 : lineStartX;
				int bfr_pos = y * bufferWidth + linex;

				if(asyncBufferBuilding) {
					synchronized (color_bfr2) {
						color_cache2.clearCacheRegion(y, linex, linewidth);
					}
				} else {
					boolean changes = false;

					for (int x = linex; x < linewidth; x++) {
						if (fowWritten.get(y*mapWidth+x)) {
							fowWritten.clear(y*mapWidth+x);
							color_cache.gotoPos(bfr_pos);
							changes = true;
							addColorTrianglesToGeometry(context, color_bfr, x, y);
						}
						bfr_pos++;
					}
					if (changes) color_cache.clearCache();
				}
			}

			if (updateGeometry) {
				for (int y = miny; y < maxy; y++) {
					int lineStartX = linestart+(y/2);

					int linewidth = (width+lineStartX) < bufferWidth ? width+lineStartX : bufferWidth;
					int linex = lineStartX < 0 ? 0 : lineStartX;
					int bfr_pos = y*bufferWidth+linex;

					for (int x = linex; x < linewidth; x++) {
						if(mapInvalid.get(bfr_pos)) {
							mapInvalid.clear(bfr_pos);
							shape_bfr.rewind();
							addTrianglesToGeometry(context, shape_bfr, x, y);
							shape_bfr.rewind();
							context.getGl().updateBufferAt(shapeHandle, bfr_pos * BYTES_PER_FIELD_SHAPE, shape_bfr);
						}
						bfr_pos++;
					}
				}
				updateGeometry = false;
			}
		} catch (IllegalBufferException e) {
			e.printStackTrace();
		}
	}

	private synchronized void invalidateShapePoint(int x, int y) {
		if(x > bufferWidth || y > bufferHeight || x < 0 || y < 0) return;
		updateGeometry = true;
		mapInvalid.set(y*bufferWidth+x);
	}

	/**
	 * Adds the two triangles for a point to the list of verteces
	 */
	private void addTrianglesToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y) {
		addTriangleToGeometry(context, buffer, x, y, true,x*37 + y*17);
		addTriangleToGeometry(context, buffer, x, y, false, x);
	}

	private void addColorTrianglesToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y) {
		addColorPointToGeometry(context, buffer, x, y);
		addColorPointToGeometry(context, buffer, x, y + 1);
		addColorPointToGeometry(context, buffer, x + 1, y + 1);

		addColorPointToGeometry(context, buffer, x, y);
		addColorPointToGeometry(context, buffer, x + 1, y + 1);
		addColorPointToGeometry(context, buffer, x + 1, y);
	}

	private void addTriangleToGeometry(MapDrawContext context, ByteBuffer buffer, int x1, int y, boolean up, int useSecondParameter) {
		int y1 = y + (up?1:0);
		int x2 = x1 + (up?0:1);
		int y2 = y + (up?0:1);
		int x3 = x1 + 1;
		int y3 = y + (up?1:0);

		ELandscapeType leftLandscape = context.getLandscape(x1, y1);
		ELandscapeType aLandscape = context.getLandscape(x2, y2);
		ELandscapeType rightLandscape = context.getLandscape(x3, y3);

		boolean useSecond = (useSecondParameter&1) == 0;
		float[] texturePos;
		int textureIndex;
		int orientationIndex = up?0:1;
		if (aLandscape == leftLandscape && aLandscape == rightLandscape) {
			textureIndex = aLandscape.getImageNumber();
			texturePos = ETextureOrientation.CONTINUOS[orientationIndex];
		} else if (leftLandscape == rightLandscape) {
			texturePos = ETextureOrientation.ORIENTATION[orientationIndex];
			textureIndex = getBorder(leftLandscape, aLandscape, useSecond);
		} else if (leftLandscape == aLandscape) {
			texturePos = ETextureOrientation.LEFT[orientationIndex];
			textureIndex = getBorder(leftLandscape, rightLandscape, useSecond);
		} else {
			texturePos = ETextureOrientation.RIGHT[orientationIndex];
			textureIndex = getBorder(up?aLandscape:rightLandscape, leftLandscape, useSecond);
		}

		int[] positions = TEXTURE_POSITIONS[textureIndex];
		// texture position
		int addDx = 0;
		int addDy = 0;
		if (positions[2] >= 2) {
			addDx = x1 * DrawConstants.DISTANCE_X - y * DrawConstants.DISTANCE_X / 2;
			addDy = y * DrawConstants.DISTANCE_Y;
			addDx = realModulo(addDx, (positions[2] - 1) * TEXTURE_GRID);
			addDy = realModulo(addDy, (positions[2] - 1) * TEXTURE_GRID);
		}
		addDx += positions[0] * TEXTURE_GRID;
		addDy += positions[1] * TEXTURE_GRID;

		{
			// left
			float u = (texturePos[0] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[1] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, up?x2:x1, up?y2:y1, u, v);
		}
		{
			// bottom
			float u = (texturePos[2] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[3] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, up?x1:x2, up?y1:y2, u, v);
		}
		{
			// right
			float u = (texturePos[4] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[5] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, x3, y3, u, v);
		}

	}


	private void addPointToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y, float u, float v) {
		buffer.putFloat(x);
		buffer.putFloat(y);
		buffer.putFloat(hasdgp ? dgpHeightGrid[y*mapWidth+x] : context.getHeight(x, y));
		buffer.putFloat(u);
		buffer.putFloat(v);
	}

	private void addColorPointToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y) {
		float fColor;
		if((x <= 0 || x >= mapWidth - 2 || y <= 0 || y >= mapHeight - 2 || ((hasdgp ? dgpVisibleStatus[x][y] : context.getVisibleStatus(x, y)) <= 0) && fowEnabled)) {
			fColor = 0;
		} else {
			int dHeight;
			if(hasdgp) {
				dHeight = dgpHeightGrid[(y-1)*mapWidth+x] - dgpHeightGrid[y*mapWidth+x];
			} else {
				dHeight = context.getHeight(x, y-1) - context.getHeight(x, y);
			}
			fColor = 0.85f + dHeight * .15f;
			if (fColor > 1.0f) {
				fColor = 1.0f;
			} else if (fColor < 0.4f) {
				fColor = 0.4f;
			}
			if(fowEnabled) fColor *= dgpVisibleStatus[x][y] / (float)CommonConstants.FOG_OF_WAR_VISIBLE;
		}

		if(useFloatColors) {
			buffer.putFloat(fColor);
		} else {
			byte color;
			fColor *= 255f;
			color = (byte) (int) fColor;
			buffer.put(color);
			buffer.put(color);
			buffer.put(color);
			buffer.put((byte) 255);
		}
	}

	private static int realModulo(int number, int modulo) {
		if (number >= 0) {
			return number % modulo;
		} else {
			return number % modulo + modulo;
		}
	}

	@Override
	public void backgroundShapeChangedAt(int x, int y) {
		backgroundColorLineChangedAt(x, y, 1);
		invalidateShapePoint(x, y);
		invalidateShapePoint(x - 1, y);
		invalidateShapePoint(x - 1, y - 1);
		invalidateShapePoint(x, y - 1);
	}

	private void updateLine(int y, int x1, int x2) {
		if(asyncBufferBuilding) {
			synchronized (color_bfr2) {
				color_cache2.gotoLine(y,x1, x2 - x1);
				for (int i = x1; i != x2; i++) {
					addColorTrianglesToGeometry(asyncAccessContext, color_bfr2, i, y);
				}
			}
		} else {
			fowWritten.set(y*mapWidth+x1, y*mapWidth+x2);
		}
	}

	@Override
	public void backgroundColorLineChangedAt(int x, int y, int length) {
		if(y == bufferHeight) return;

		int x2 = x + length;
		if(x != 0) x = x-1;
		if(x2 < bufferWidth) x2 = x2+1;
		if(x2 > bufferWidth) x2 = bufferWidth;

		updateLine(y, x, x2);
		if(y > 0) updateLine(y-1, x, x2);
		if(y < bufferHeight-1) updateLine(y+1, x, x2);
	}

	/**
	 * Invalidates the background texture.
	 */
	static void invalidateTexture() {
		texture = null;
	}
}
