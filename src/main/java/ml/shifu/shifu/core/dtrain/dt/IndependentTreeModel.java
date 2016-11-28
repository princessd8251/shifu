/*
 * Copyright [2013-2016] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.dtrain.dt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ml.shifu.shifu.core.dtrain.CommonConstants;

/**
 * {@link IndependentTreeModel} depends no other classes which is easy to deploy model in production.
 * 
 * <p>
 * {@link #loadFromStream(InputStream)} should be the only interface to load a tree model object.
 * 
 * <p>
 * To predict data for tree model, call {@link #compute(Map)} or {@link #compute(double[])}
 * 
 * @author Zhang David (pengzhang@paypal.com)
 */
public class IndependentTreeModel {

    /**
     * Mapping for (ColumnNum, ColumnName)
     */
    private Map<Integer, String> numNameMapping;

    /**
     * Mapping for (ColumnNum, Category List) for categorical feature
     */
    private Map<Integer, List<String>> categoricalColumnNameNames;

    /**
     * Mapping for (ColumnNum, Map(Category, CategoryIndex) for categorical feature
     */
    private Map<Integer, Map<String, Integer>> columnCategoryIndexMapping;

    /**
     * Mapping for (ColumnNum, index in double[] array)
     */
    private Map<Integer, Integer> columnNumIndexMapping;

    /**
     * A list of tree models, can be RF or GBT
     */
    private List<TreeNode> trees;

    /**
     * Weights per each tree in {@link #trees}
     */
    private List<Double> weights;

    /**
     * If it is for GBT
     */
    private boolean isGBDT = false;

    /**
     * If model is for classification
     */
    private boolean isClassification = false;

    /**
     * GBT model results is not in [0, 1], set {@link #isConvertToProb} to true will normalize model score to [0, 1]
     */
    private boolean isConvertToProb = false;

    /**
     * {@link #lossStr} is used to validate, if continuous model training but different loss type, should be failed.
     * TODO add validation
     */
    private String lossStr;

    /**
     * RF or GBT
     */
    private String algorithm;

    /**
     * # of input node
     */
    private int inputNode;

    public IndependentTreeModel(Map<Integer, String> numNameMapping,
            Map<Integer, List<String>> categoricalColumnNameNames,
            Map<Integer, Map<String, Integer>> columnCategoryIndexMapping, Map<Integer, Integer> columnNumIndexMapping,
            List<TreeNode> trees, List<Double> weights, boolean isGBDT, boolean isClassification,
            boolean isConvertToProb, String lossStr, String algorithm, int inputNode) {
        this.numNameMapping = numNameMapping;
        this.categoricalColumnNameNames = categoricalColumnNameNames;
        this.columnCategoryIndexMapping = columnCategoryIndexMapping;
        this.columnNumIndexMapping = columnNumIndexMapping;
        this.trees = trees;
        this.weights = weights;
        this.isGBDT = isGBDT;
        this.isClassification = isClassification;
        this.isConvertToProb = isConvertToProb;
        this.lossStr = lossStr;
        this.algorithm = algorithm;
        this.inputNode = inputNode;
    }

    /**
     * Given double array data, compute score values of tree model.
     * 
     * @param data
     *            data array includes only effective column data, numeric value is real value, categorical feature value
     *            is index of binCategoryList.
     * @return if classification mode, return array of all scores of trees
     *         if regression of RF, return array with only one element which is avg score of all tree model scores
     *         if regression of GBT, return array with only one element which is score of the GBT model
     */
    public double[] compute(double[] data) {
        double predictSum = 0d;
        double weightSum = 0d;
        double[] scores = new double[this.trees.size()];
        for(int i = 0; i < this.trees.size(); i++) {
            TreeNode treeNode = this.trees.get(i);
            Double weight = this.weights.get(i);
            weightSum += weight;
            double score = predictNode(treeNode.getNode(), data);
            scores[i] = score;
            predictSum += score * weight;
        }

        if(this.isClassification) {
            return scores;
        } else {
            double finalPredict;
            if(this.isGBDT) {
                if(this.isConvertToProb) {
                    finalPredict = convertToProb(predictSum);
                } else {
                    finalPredict = predictSum;
                }
            } else {
                finalPredict = predictSum / weightSum;
            }
            return new double[] { finalPredict };
        }
    }

    /**
     * Given dataMap with format (columnName, value), compute score values of tree model
     * 
     * @param dataMap
     *            dataMap for (columnName, value), numeric value can be double/String, categorical feature can be
     *            int(index) or category value.
     * @return if classification mode, return array of all scores of trees
     *         if regression of RF, return array with only one element which is avg score of all tree model scores
     *         if regression of GBT, return array with only one element which is score of the GBT model
     * @throws IllegalArgumentException
     *             if needed columns not in parameter dataMap
     */
    public final double[] compute(Map<String, Object> dataMap) {
        double predictSum = 0d;
        double weightSum = 0d;
        double[] scores = new double[this.trees.size()];
        for(int i = 0; i < this.trees.size(); i++) {
            TreeNode treeNode = this.trees.get(i);
            Double weight = this.weights.get(i);
            weightSum += weight;
            double score = predictNode(treeNode.getNode(), dataMap);
            scores[i] = score;
            predictSum += score * weight;
        }

        if(this.isClassification) {
            return scores;
        } else {
            double finalPredict;
            if(this.isGBDT) {
                if(this.isConvertToProb) {
                    finalPredict = convertToProb(predictSum);
                } else {
                    finalPredict = predictSum;
                }
            } else {
                finalPredict = predictSum / weightSum;
            }
            return new double[] { finalPredict };
        }
    }

    /**
     * Covert score to probability value which are in [0, 1], for GBT regression, scores can not be [0, 1].
     */
    protected double convertToProb(double score) {
        // sigmoid function to covert to [0, 1], TODO tune such function to get better [0, 1]
        return 1 / (1 + Math.min(1.0E19, Math.exp(-(score * 3 - 2.0602792296384576d))));
    }

    private double predictNode(Node topNode, double[] data) {
        Node currNode = topNode;
        Split split = currNode.getSplit();
        if(split == null || currNode.isRealLeaf()) {
            if(this.isClassification) {
                return currNode.getPredict().getClassValue();
            } else {
                return currNode.getPredict().getPredict();
            }
        }

        Node nextNode = null;
        double value = data[this.columnNumIndexMapping.get(split.getColumnNum())];

        if(split.getFeatureType().isNumerical()) {
            // value is real numeric value and no need to transform to binLowestValue
            if(value < split.getThreshold()) {
                nextNode = currNode.getLeft();
            } else {
                nextNode = currNode.getRight();
            }
        } else if(split.getFeatureType().isCategorical()) {
            int indexValue = -1;
            if(Double.compare(value, 0d) < 0
                    || Double.compare(value, categoricalColumnNameNames.get(split.getColumnNum()).size()) >= 0) {
                indexValue = categoricalColumnNameNames.get(split.getColumnNum()).size();
            } else {
                // value is category index + 0.1d is to avoid 0.9999999 converted to 0
                indexValue = (int) (value + 0.1d);
            }
            if(split.getLeftCategories().contains(indexValue)) {
                nextNode = currNode.getLeft();
            } else {
                nextNode = currNode.getRight();
            }
        }

        assert nextNode != null;
        return predictNode(nextNode, data);
    }

    private double predictNode(Node topNode, Map<String, Object> dataMap) {
        Node currNode = topNode;
        Split split = currNode.getSplit();
        if(split == null || currNode.isRealLeaf()) {
            if(this.isClassification) {
                return currNode.getPredict().getClassValue();
            } else {
                return currNode.getPredict().getPredict();
            }
        }

        Node nextNode = null;
        Object obj = dataMap.get(numNameMapping.get(split.getColumnNum()));

        if(obj == null) {
            throw new IllegalArgumentException("Current model need column " + numNameMapping.get(split.getColumnNum())
                    + " but not found in dataMap, please check your input");
        }

        if(split.getFeatureType().isNumerical()) {
            double value = 0d;
            if(obj instanceof Double) {
                value = ((Double) obj).doubleValue();
            } else {
                value = Double.parseDouble(obj.toString());
            }

            // value is real numeric value and no need to transform to binLowestValue
            if(value < split.getThreshold()) {
                nextNode = currNode.getLeft();
            } else {
                nextNode = currNode.getRight();
            }
        } else if(split.getFeatureType().isCategorical()) {
            double indexValue = -1d;
            if(obj instanceof Number) {
                indexValue = ((Number) obj).doubleValue();
            } else {
                Integer intIndex = columnCategoryIndexMapping.get(split.getColumnNum()).get(obj.toString());
                if(intIndex == null || intIndex < 0
                        || intIndex >= categoricalColumnNameNames.get(split.getColumnNum()).size()) {
                    // last one is for invalid category
                    intIndex = categoricalColumnNameNames.get(split.getColumnNum()).size();
                }
                indexValue = intIndex * 1d;
            }
            if(split.getLeftCategories().contains((int) (indexValue + 0.1d))) {
                nextNode = currNode.getLeft();
            } else {
                nextNode = currNode.getRight();
            }
        }

        assert nextNode != null;
        return predictNode(nextNode, dataMap);
    }

    /**
     * @return the lossStr
     */
    public String getLossStr() {
        return lossStr;
    }

    /**
     * @param lossStr
     *            the lossStr to set
     */
    public void setLossStr(String lossStr) {
        this.lossStr = lossStr;
    }

    /**
     * @return the numNameMapping
     */
    public Map<Integer, String> getNumNameMapping() {
        return numNameMapping;
    }

    /**
     * @return the categoricalColumnNameNames
     */
    public Map<Integer, List<String>> getCategoricalColumnNameNames() {
        return categoricalColumnNameNames;
    }

    /**
     * @return the columnCategoryIndexMapping
     */
    public Map<Integer, Map<String, Integer>> getColumnCategoryIndexMapping() {
        return columnCategoryIndexMapping;
    }

    /**
     * @return the columnNumIndexMapping
     */
    public Map<Integer, Integer> getColumnNumIndexMapping() {
        return columnNumIndexMapping;
    }

    /**
     * @return the trees
     */
    public List<TreeNode> getTrees() {
        return trees;
    }

    /**
     * @return the weights
     */
    public List<Double> getWeights() {
        return weights;
    }

    /**
     * @return the isGBDT
     */
    public boolean isGBDT() {
        return isGBDT;
    }

    /**
     * @return the isClassification
     */
    public boolean isClassification() {
        return isClassification;
    }

    /**
     * @return the isConvertToProb
     */
    public boolean isConvertToProb() {
        return isConvertToProb;
    }

    /**
     * @param numNameMapping
     *            the numNameMapping to set
     */
    public void setNumNameMapping(Map<Integer, String> numNameMapping) {
        this.numNameMapping = numNameMapping;
    }

    /**
     * @param categoricalColumnNameNames
     *            the categoricalColumnNameNames to set
     */
    public void setCategoricalColumnNameNames(Map<Integer, List<String>> categoricalColumnNameNames) {
        this.categoricalColumnNameNames = categoricalColumnNameNames;
    }

    /**
     * @param columnCategoryIndexMapping
     *            the columnCategoryIndexMapping to set
     */
    public void setColumnCategoryIndexMapping(Map<Integer, Map<String, Integer>> columnCategoryIndexMapping) {
        this.columnCategoryIndexMapping = columnCategoryIndexMapping;
    }

    /**
     * @param columnNumIndexMapping
     *            the columnNumIndexMapping to set
     */
    public void setColumnNumIndexMapping(Map<Integer, Integer> columnNumIndexMapping) {
        this.columnNumIndexMapping = columnNumIndexMapping;
    }

    /**
     * @param trees
     *            the trees to set
     */
    public void setTrees(List<TreeNode> trees) {
        this.trees = trees;
    }

    /**
     * @param weights
     *            the weights to set
     */
    public void setWeights(List<Double> weights) {
        this.weights = weights;
    }

    /**
     * @param isGBDT
     *            the isGBDT to set
     */
    public void setGBDT(boolean isGBDT) {
        this.isGBDT = isGBDT;
    }

    /**
     * @param isClassification
     *            the isClassification to set
     */
    public void setClassification(boolean isClassification) {
        this.isClassification = isClassification;
    }

    /**
     * @param isConvertToProb
     *            the isConvertToProb to set
     */
    public void setConvertToProb(boolean isConvertToProb) {
        this.isConvertToProb = isConvertToProb;
    }

    /**
     * @return the algorithm
     */
    public String getAlgorithm() {
        return algorithm;
    }

    /**
     * @param algorithm
     *            the algorithm to set
     */
    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    /**
     * @return the inputNode
     */
    public int getInputNode() {
        return inputNode;
    }

    /**
     * @param inputNode
     *            the inputNode to set
     */
    public void setInputNode(int inputNode) {
        this.inputNode = inputNode;
    }

    /**
     * Load model instance from stream like model0.gbt or model0.rf, by default not to convert gbt score to [0, 1]
     */
    public static IndependentTreeModel loadFromStream(InputStream input) throws IOException {
        return loadFromStream(input, false);
    }

    /**
     * Load model instance from stream like model0.gbt or model0.rf, user can specify isConvertToProb parameter
     */
    public static IndependentTreeModel loadFromStream(InputStream input, boolean isConvertToProb) throws IOException {
        DataInputStream dis = new DataInputStream(input);

        String algorithm = dis.readUTF();
        String lossStr = dis.readUTF();
        boolean isClassification = dis.readBoolean();
        boolean isOneVsAll = dis.readBoolean();
        int inputNode = dis.readInt();

        Map<Integer, String> columnIndexNameMapping = new HashMap<Integer, String>();
        Map<String, Integer> columnNameIndexMapping = new HashMap<String, Integer>();
        int size = dis.readInt();
        for(int i = 0; i < size; i++) {
            int columnIndex = dis.readInt();
            String columnName = dis.readUTF();
            columnIndexNameMapping.put(columnIndex, columnName);
            columnNameIndexMapping.put(columnName, columnIndex);
        }

        Map<Integer, List<String>> categoricalColumnNameNames = new HashMap<Integer, List<String>>();
        Map<Integer, Map<String, Integer>> columnCategoryIndexMapping = new HashMap<Integer, Map<String, Integer>>();
        size = dis.readInt();
        for(int i = 0; i < size; i++) {
            int columnIndex = dis.readInt();
            int categoryListSize = dis.readInt();
            Map<String, Integer> categoryIndexMapping = new HashMap<String, Integer>();
            List<String> categories = new ArrayList<String>();
            for(int j = 0; j < categoryListSize; j++) {
                String category = dis.readUTF();
                categoryIndexMapping.put(category, j);
                categories.add(category);
            }
            categoricalColumnNameNames.put(columnIndex, categories);
            columnCategoryIndexMapping.put(columnIndex, categoryIndexMapping);
        }

        Map<Integer, Integer> columnMapping = new HashMap<Integer, Integer>();
        int columnMappingSize = dis.readInt();
        for(int i = 0; i < columnMappingSize; i++) {
            columnMapping.put(dis.readInt(), dis.readInt());
        }

        int treeNum = dis.readInt();
        List<TreeNode> trees = new ArrayList<TreeNode>(treeNum);
        List<Double> weights = new ArrayList<Double>(treeNum);
        for(int i = 0; i < treeNum; i++) {
            TreeNode treeNode = new TreeNode();
            treeNode.readFields(dis);
            trees.add(treeNode);
            weights.add(treeNode.getLearningRate());
        }

        // if one vs all, even multiple classification, treated as regression
        return new IndependentTreeModel(columnIndexNameMapping, categoricalColumnNameNames, columnCategoryIndexMapping,
                columnMapping, trees, weights, CommonConstants.GBT_ALG_NAME.equalsIgnoreCase(algorithm),
                isClassification && !isOneVsAll, isConvertToProb, lossStr, algorithm, inputNode);
    }

}
