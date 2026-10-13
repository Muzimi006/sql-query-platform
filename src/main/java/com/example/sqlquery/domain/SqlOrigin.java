package com.example.sqlquery.domain;

/**
 * 一条 SQL 是"谁"产生的。
 *
 * <p>为什么要单独记这个：<b>AI 生成的 SQL 和用户手写的 SQL，风险是一样的</b>，
 * 甚至更高 —— 因为提需求的人可能看不懂机器写出来的东西。
 *
 * <p>但它**不能因此获得任何特权**，也不能因此被"更宽松地对待"：
 * - 它必须走**完全相同**的四道防线（见 {@code SqlValidateUtil}）；
 * - 审批人要能看出"我在审一段机器写的 SQL"，从而更仔细地看；
 * - 审计日志里要留下痕迹 —— 出问题时能回答"这条 SQL 是谁的意思"。
 */
public final class SqlOrigin {

    private SqlOrigin() {
    }

    /** 人工手写。 */
    public static final String HUMAN = "HUMAN";

    /** 由自然语言经模型生成。 */
    public static final String AI = "AI";
}
