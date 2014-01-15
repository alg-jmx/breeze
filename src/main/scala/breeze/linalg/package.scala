package breeze
/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import io.{CSVWriter, CSVReader}
import linalg.operators._
import breeze.linalg.support.{CanAxpy, CanTraverseValues, RangeSuffix, CanCopy}
import math.Semiring
import org.netlib.util.intW
import storage.DefaultArrayValue
import java.io.{File, FileReader}
import scala.reflect.ClassTag


/**
 * This package contains everything relating to Vectors, Matrices, Tensors, etc.
 *
 * If you're doing basic work, you probably want [[breeze.linalg.DenseVector]] and [[breeze.linalg.DenseMatrix]],
 * which support most operations. We also have [[breeze.linalg.SparseVector]]s and (basic!) support
 * for a sparse matrix ([[breeze.linalg.CSCMatrix]]).
 *
 * This package object contains Matlab-esque functions for interacting with tensors and matrices.
 *
 * @author dlwh
 */
package object linalg {

  /**
   * Computes y += x * a, possibly doing less work than actually doing that operation
   */
  def axpy[A, X, Y](a: A, x: X, y: Y)(implicit axpy: CanAxpy[A, X, Y]) { axpy(a,x,y) }

  /**
   * Generates a vector of linearly spaced values between a and b (inclusive).
   * The returned vector will have length elements, defaulting to 100.
   */
  def linspace(a : Double, b : Double, length : Int = 100) : DenseVector[Double] = {
    val increment = (b - a) / (length - 1)
    DenseVector.tabulate(length)(i => a + increment * i)
  }

  /**
   * Copy a T. Most tensor objects have a CanCopy implicit, which is what this farms out to.
   */
  def copy[T](t: T)(implicit canCopy: CanCopy[T]): T = canCopy(t)



  // io stuff
  /**
   * Reads in a DenseMatrix from a CSV File
   */
  def csvread(file: File,
             separator: Char=',',
             quote: Char='"',
             escape: Char='\\',
             skipLines: Int = 0): DenseMatrix[Double] = {
    val input = new FileReader(file)
    var mat = CSVReader.read(input, separator, quote, escape, skipLines)
    mat = mat.takeWhile(line => line.length != 0 && line.head.nonEmpty) // empty lines at the end
    input.close()
    if(mat.length == 0) {
      DenseMatrix.zeros[Double](0,0)
    } else {
      DenseMatrix.tabulate(mat.length,mat.head.length)((i,j)=>mat(i)(j).toDouble)
    }
  }

  def csvwrite(file: File, mat: Matrix[Double],
               separator: Char=',',
               quote: Char='\0',
               escape: Char='\\',
               skipLines: Int = 0) {
    CSVWriter.writeFile(file, IndexedSeq.tabulate(mat.rows,mat.cols)(mat(_,_).toString), separator, quote, escape)
  }

  /** for adding slicing */
  implicit class RichIntMethods(val x: Int) extends AnyVal {
    def until(z: ::.type) = new RangeSuffix(x)
  }



  import math.Ring
  import com.github.fommil.netlib.LAPACK.{getInstance=>lapack}

/**
 * Basic linear algebraic operations.
 *
 * @author dlwh,dramage,retronym,afwlehmann,lancelet
 */
  //  import breeze.linalg._

  private[linalg] def requireNonEmptyMatrix[V](mat: Matrix[V]) =
    if (mat.cols == 0 || mat.rows == 0)
      throw new MatrixEmptyException

  private[linalg] def requireSquareMatrix[V](mat: Matrix[V]) =
    if (mat.rows != mat.cols)
      throw new MatrixNotSquareException

  private[linalg] def requireSymmetricMatrix[V](mat: Matrix[V]) = {
    requireSquareMatrix(mat)

    for (i <- 0 until mat.rows; j <- 0 until i)
      if (mat(i,j) != mat(j,i))
        throw new MatrixNotSymmetricException
  }



  /**
   * Returns the Kronecker product of the two matrices a and b,
   * usually denoted a ⊗ b.
   */
  def kron[V1,V2, M,RV](a : DenseMatrix[V1], b : M)(implicit mul : OpMulScalar.Impl2[V1, M, DenseMatrix[RV]],
                                                    asMat: M<:<Matrix[V2],
                                                    man: ClassTag[RV],
                                                    dfv: DefaultArrayValue[RV]) : DenseMatrix[RV] = {
    val result: DenseMatrix[RV] = DenseMatrix.zeros[RV](a.rows * b.rows, a.cols * b.cols)
    for( ((r,c),av) <- a.activeIterator) {
      result((r*b.rows) until ((r+1)*b.rows), (c * b.cols) until ((c+1) * b.cols)) := mul(av, b)
    }
    result
  }

  /**
   * Vector cross product of 3D vectors a and b.
   */
  def cross[V1](a: DenseVector[V1], b: DenseVector[V1])(implicit ring: Ring[V1], man: ClassTag[V1]): DenseVector[V1] = {
    require(a.length == 3)
    require(b.length == 3)
    DenseVector(
      ring.-(ring.*(a(1), b(2)), ring.*(a(2), b(1))),
      ring.-(ring.*(a(2), b(0)), ring.*(a(0), b(2))),
      ring.-(ring.*(a(0), b(1)), ring.*(a(1), b(0)))
    )
  }

  /**
   * Returns the rank of each element in the given vector, adjusting for
   * ties.
   */
  def ranks[V:Ordering](x : Vector[V]): Array[Double] = {
    val a = x
    val as = a.argsort
    val rv = new Array[Double](as.length)
    var i = 0
    while (i < as.length) {
      // count number of tied values at rank i
      var numTiedValuesAtI = 1
      while (i + numTiedValuesAtI < as.length && a(as(i + numTiedValuesAtI)) == a(as(i))) {
        numTiedValuesAtI += 1
      }

      // set return value for next numTiedValuesAtI indexes in as
      val rank = 1 + i + (numTiedValuesAtI - 1) / 2.0
      var j = 0
      while (j < numTiedValuesAtI) {
        rv(as(i + j)) = rank
        j += 1
      }

      i += numTiedValuesAtI
    }

    rv
  }

  /**
   * The lower triangular portion of the given real quadratic matrix X. Note
   * that no check will be performed regarding the symmetry of X.
   */
  def lowerTriangular[T: Semiring: ClassTag:DefaultArrayValue](X: Matrix[T]): DenseMatrix[T] = {
    val N = X.rows
    DenseMatrix.tabulate(N, N)( (i, j) =>
      if(j <= i) X(i,j)
      else implicitly[Semiring[T]].zero
    )
  }

  /**
   * The upper triangular portion of the given real quadratic matrix X. Note
   * that no check will be performed regarding the symmetry of X.
   */
  def upperTriangular[T: Semiring: ClassTag: DefaultArrayValue](X: Matrix[T]): DenseMatrix[T] = {
    val N = X.rows
    DenseMatrix.tabulate(N, N)( (i, j) =>
      if(j >= i) X(i,j)
      else implicitly[Semiring[T]].zero
    )
  }

  /**
   * Computes the cholesky decomposition A of the given real symmetric
   * positive definite matrix X such that X = A A.t.
   *
   * XXX: For higher dimensionalities, the return value really should be a
   *      sparse matrix due to its inherent lower triangular nature.
   */
  def cholesky(X: Matrix[Double]): DenseMatrix[Double] = {
    requireNonEmptyMatrix(X)

    // As LAPACK doesn't check if the given matrix is in fact symmetric,
    // we have to do it here (or get rid of this time-waster as long as
    // the caller of this function is clearly aware that only the lower
    // triangular portion of the given matrix is used and there is no
    // check for symmetry).
    requireSymmetricMatrix(X)

    // Copy the lower triangular part of X. LAPACK will store the result in A
    val A: DenseMatrix[Double] = lowerTriangular(X)

    val N = X.rows
    val info = new intW(0)
      lapack.dpotrf(
        "L" /* lower triangular */,
        N /* number of rows */, A.data, scala.math.max(1, N) /* LDA */,
        info
      )
    // A value of info.`val` < 0 would tell us that the i-th argument
    // of the call to dpotrf was erroneous (where i == |info.`val`|).
    assert(info.`val` >= 0)

    if (info.`val` > 0)
      throw new NotConvergedException(NotConvergedException.Iterations)

    A
  }






  /**
   * Computes the rank of a DenseMatrix[Double].
   *
   * The rank of the matrix is computed using the SVD method.  The singular values of the SVD
   * which are greater than a specified tolerance are counted.
   *
   * @param m matrix for which to compute the rank
   * @param tol optional tolerance for singular values.  If not supplied, the default
   *   tolerance is: max(m.cols, m.rows) * eps * sigma_max, where
   *   eps is the machine epsilon and sigma_max is the largest singular value of m.
   * @return the rank of the matrix (number of singular values)
   */
  def rank(m: DenseMatrix[Double], tol: Option[Double] = None): Int = {
    val (u, s, vt) = svd(m)
    val useTol = tol.getOrElse {
      // we called LAPACK for the SVD method, so this is the LAPACK definition of eps.
      val eps: Double = 2.0 * lapack.dlamch("e")
      scala.math.max(m.cols, m.rows) * eps * s.max
    }
    s.data.count(_ > useTol)  // TODO: Use DenseVector[_].count() if/when that is implemented
  }


}


