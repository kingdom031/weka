/*
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */


/*
 *    ClusterMembership.java
 *    Copyright (C) 2004 Mark Hall
 *
 */

package weka.filters.unsupervised.attribute;

import weka.filters.Filter;
import weka.filters.UnsupervisedFilter;
import weka.filters.unsupervised.attribute.Remove;
import weka.clusterers.Clusterer;
import weka.core.Attribute;
import weka.core.Instances;
import weka.core.Instance;
import weka.core.OptionHandler;
import weka.core.Range;
import weka.core.FastVector;
import weka.core.Option;
import weka.core.Utils;
import java.util.Enumeration;
import java.util.Vector;

/** 
 * A filter that uses a clusterer to obtain cluster membership probabilites
 * for each input instance and outputs them as new instances. <p>
 *
 * Valid filter-specific options are: <p>
 *
 * Full class name of clusterer to use. Clusterer options may be
 * specified at the end following a -- .(required)<p>
 *   
 * -I range string <br>
 * The range of attributes the clusterer should ignore. Note: 
 * the class attribute (if set) is automatically ignored during clustering.<p>
 *
 * @author Mark Hall (mhall@cs.waikato.ac.nz)
 * @version $Revision: 1.2 $
 */
public class ClusterMembership extends Filter implements UnsupervisedFilter, 
							 OptionHandler {

  /** The clusterer */
  protected Clusterer m_clusterer = new weka.clusterers.EM();

  /** Range of attributes to ignore */
  protected Range m_ignoreAttributesRange = null;

  /** Filter for removing attributes */
  protected Filter m_removeAttributes = new Remove();

  /**
   * Sets the format of the input instances.
   *
   * @param instanceInfo an Instances object containing the input instance
   * structure (any instances contained in the object are ignored - only the
   * structure is required).
   * @return true if the outputFormat may be collected immediately
   * @exception Exception if the inputFormat can't be set successfully 
   */ 
  public boolean setInputFormat(Instances instanceInfo) throws Exception {
    
    super.setInputFormat(instanceInfo);
    m_removeAttributes = null;

    return false;
  }

  /**
   * Signify that this batch of input to the filter is finished.
   *
   * @return true if there are instances pending output
   * @exception IllegalStateException if no input structure has been defined 
   */  
  public boolean batchFinished() throws Exception {

    if (getInputFormat() == null) {
      throw new IllegalStateException("No input instance format defined");
    }

    if (outputFormatPeek() == null) {

      Instances toFilter = getInputFormat();
      Instances toFilterIgnoringAttributes = toFilter;
      
      // filter out attributes if necessary
      if (m_ignoreAttributesRange != null || toFilter.classIndex() >= 0) {
	toFilterIgnoringAttributes = new Instances(toFilter);
	m_removeAttributes = new Remove();
	String rangeString = "";
	if (m_ignoreAttributesRange != null) {
	  rangeString += m_ignoreAttributesRange.getRanges();
	}
	if (toFilter.classIndex() >= 0) {
	  if (rangeString.length() > 0) {
	    rangeString += (","+(toFilter.classIndex()+1));	  
	  } else {
	    rangeString = ""+(toFilter.classIndex()+1);
	  }
	}
	((Remove)m_removeAttributes).setAttributeIndices(rangeString);
	((Remove)m_removeAttributes).setInvertSelection(false);
	m_removeAttributes.setInputFormat(toFilter);
	for (int i = 0; i < toFilter.numInstances(); i++) {
	  m_removeAttributes.input(toFilter.instance(i));
	}
	m_removeAttributes.batchFinished();
	toFilterIgnoringAttributes = m_removeAttributes.getOutputFormat();
	
	Instance tempInst;
	while ((tempInst = m_removeAttributes.output()) != null) {
	  toFilterIgnoringAttributes.add(tempInst);
	}
      }
      
      // build the clusterer
      m_clusterer.buildClusterer(toFilterIgnoringAttributes);
      
      // create output dataset
      int numAtts = (toFilter.classIndex() >=0)
	? m_clusterer.numberOfClusters() + 1
	: m_clusterer.numberOfClusters();
      
      FastVector attInfo = new FastVector(numAtts);
      for (int i = 0; i < m_clusterer.numberOfClusters(); i++) {
	attInfo.addElement(new Attribute("pCluster"+i));
      }
      if (toFilter.classIndex() >= 0) {
	attInfo.addElement(toFilter.classAttribute().copy());
      }
      Instances filtered = new Instances(toFilter.relationName()+"_clusterMembership",
					 attInfo, 0);
      if (toFilter.classIndex() >= 0) {
	filtered.setClassIndex(filtered.numAttributes() - 1);
      }
      setOutputFormat(filtered);

      // build new daaset
      for (int i = 0; i < toFilter.numInstances(); i++) {
	convertInstance(toFilter.instance(i));
      }
    }
    flushInput();

    m_NewBatch = true;
    return (numPendingOutput() != 0);
  }

  /**
   * Input an instance for filtering. Ordinarily the instance is processed
   * and made available for output immediately. Some filters require all
   * instances be read before producing output.
   *
   * @param instance the input instance
   * @return true if the filtered instance may now be
   * collected with output().
   * @exception IllegalStateException if no input format has been defined.
   */
  public boolean input(Instance instance) throws Exception {

    if (getInputFormat() == null) {
      throw new IllegalStateException("No input instance format defined");
    }
    if (m_NewBatch) {
      resetQueue();
      m_NewBatch = false;
    }
    
    if (outputFormatPeek() != null) {
      convertInstance(instance);
      return true;
    }

    bufferInput(instance);
    return false;
  }

  /**
   * Convert a single instance over. The converted instance is added to 
   * the end of the output queue.
   *
   * @param instance the instance to convert
   */
  protected void convertInstance(Instance instance) throws Exception {
    
    double [] probs;
    if (m_removeAttributes != null) {
      m_removeAttributes.input(instance);
      probs = m_clusterer.distributionForInstance(m_removeAttributes.output());
    } else {
      probs = m_clusterer.distributionForInstance(instance);
    }
    
    // set up values
    double [] instanceVals = new double[outputFormatPeek().numAttributes()];
    for (int j = 0; j < probs.length; j++) {
      instanceVals[j] = probs[j];
    }
    if (instance.classIndex() >= 0) {
      instanceVals[instanceVals.length - 1] = instance.classValue();
    }
    
    push(new Instance(instance.weight(), instanceVals));
  }

  /**
   * Returns an enumeration describing the available options.
   *
   * @return an enumeration of all the available options.
   */
  public Enumeration listOptions() {
    
    Vector newVector = new Vector(2);
    
    newVector.
      addElement(new Option("\tFull name of clusterer to use (required).\n"
			    + "\teg: weka.clusterers.EM",
			    "W", 1, "-W <clusterer name>"));

    newVector.
      addElement(new Option("\tThe range of attributes the clusterer should ignore."
			    +"\n\t(the class attribute is automatically ignored)",
			    "I", 1,"-I <att1,att2-att4,...>"));

    return newVector.elements();
  }

  /**
   * Parses the options for this object. Valid options are: <p>
   *
   * -W clusterer string <br>
   * Full class name of clusterer to use. Clusterer options may be
   * specified at the end following a -- .(required)<p>
   *   
   * -I range string <br>
   * The range of attributes the clusterer should ignore. Note: 
   * the class attribute (if set) is automatically ignored during clustering.<p>
   *
   * @param options the list of options as an array of strings
   * @exception Exception if an option is not supported
   */
  public void setOptions(String[] options) throws Exception {

    String clustererString = Utils.getOption('W', options);
    if (clustererString.length() == 0) {
      throw new Exception("A clusterer must be specified"
			  + " with the -W option.");
    }
    setClusterer((Clusterer)Utils.forName(Clusterer.class, clustererString,
				Utils.partitionOptions(options)));

    setIgnoredAttributeIndices(Utils.getOption('I', options));
    Utils.checkForRemainingOptions(options);
  }

  /**
   * Gets the current settings of the filter.
   *
   * @return an array of strings suitable for passing to setOptions
   */
  public String [] getOptions() {

    String [] clustererOptions = new String [0];
    if ((m_clusterer != null) &&
	(m_clusterer instanceof OptionHandler)) {
      clustererOptions = ((OptionHandler)m_clusterer).getOptions();
    }
    String [] options = new String [clustererOptions.length + 5];
    int current = 0;

    if (!getIgnoredAttributeIndices().equals("")) {
      options[current++] = "-I";
      options[current++] = getIgnoredAttributeIndices();
    }
    
    if (m_clusterer != null) {
      options[current++] = "-W"; 
      options[current++] = getClusterer().getClass().getName();
    }

    options[current++] = "--";
    System.arraycopy(clustererOptions, 0, options, current,
		     clustererOptions.length);
    current += clustererOptions.length;
    
    while (current < options.length) {
      options[current++] = "";
    }
    return options;
  }

  /**
   * Returns a string describing this filter
   *
   * @return a description of the filter suitable for
   * displaying in the explorer/experimenter gui
   */
  public String globalInfo() {

    return "A filter that uses a clusterer to generate cluster membership "
      + "probabilities; filtered instances are composed of these probabilities "
      + "plus the class attribute (if set in the input data). The class attribute "
      + "(if set) and any user specified attributes are ignored during the "
      + "clustering operation";
  }
  
  /**
   * Returns a description of this option suitable for display
   * as a tip text in the gui.
   *
   * @return description of this option
   */
  public String clustererTipText() {
    return "The clusterer that will generate membership probabilities for instances.";
  }

  /**
   * Set the clusterer for use in filtering
   *
   * @param newClusterer the clusterer to use
   */
  public void setClusterer(Clusterer newClusterer) {
    m_clusterer = newClusterer;
  }

  /**
   * Get the clusterer used by this filter
   *
   * @return the clusterer used
   */
  public Clusterer getClusterer() {
    return m_clusterer;
  }

  /**
   * Returns the tip text for this property
   *
   * @return tip text for this property suitable for
   * displaying in the explorer/experimenter gui
   */
  public String ignoredAttributeIndicesTipText() {

    return "The range of attributes to be ignored by the clusterer. eg: first-3,5,9-last";
  }

  /**
   * Gets ranges of attributes to be ignored.
   *
   * @return a string containing a comma-separated list of ranges
   */
  public String getIgnoredAttributeIndices() {

    if (m_ignoreAttributesRange == null) {
      return "";
    } else {
      return m_ignoreAttributesRange.getRanges();
    }
  }

  /**
   * Sets the ranges of attributes to be ignored. If provided string
   * is null, no attributes will be ignored.
   *
   * @param rangeList a string representing the list of attributes. 
   * eg: first-3,5,6-last
   * @exception IllegalArgumentException if an invalid range list is supplied 
   */
  public void setIgnoredAttributeIndices(String rangeList) {

    if ((rangeList == null) || (rangeList.length() == 0)) {
      m_ignoreAttributesRange = null;
    } else {
      m_ignoreAttributesRange = new Range();
      m_ignoreAttributesRange.setRanges(rangeList);
    }
  }

  /**
   * Main method for testing this class.
   *
   * @param argv should contain arguments to the filter: use -h for help
   */
  public static void main(String [] argv) {

    try {
      if (Utils.getFlag('b', argv)) {
 	Filter.batchFilterFile(new ClusterMembership(), argv); 
      } else {
	Filter.filterFile(new ClusterMembership(), argv);
      }
    } catch (Exception ex) {
      System.out.println(ex.getMessage());
    }
  }
}
  
