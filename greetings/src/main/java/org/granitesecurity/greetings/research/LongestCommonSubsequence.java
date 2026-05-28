package org.granitesecurity.greetings.research;

public class LongestCommonSubsequence {
    static int[][] dp;

    static void main() {
        String text1 = "abcdez";
        String text2 = "aceyyz";
        int result = longestCommonSubsequence(text1, text2);
        System.out.println("Length of LCS: " + result);

        printDP();
        System.out.println("Longest Common Subsequence: " + buildLongestCommonSubsequenceFromDP(text1, text2, dp));
    }

    private static String buildLongestCommonSubsequenceFromDP(String text1, String text2, int[][] dp) {
        StringBuilder sb = new StringBuilder();
        int i = text1.length();
        int j = text2.length();
        while (i > 0 && j > 0) {
            if (text1.charAt(i - 1) == text2.charAt(j - 1)) {
                sb.append(text1.charAt(i - 1));
                i--;
                j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }
        return sb.reverse().toString();

    }

    public static int longestCommonSubsequence(String text1, String text2) {
        int len1 = text1.length();
        int len2 = text2.length();
        char[] t1 = text1.toCharArray();
        char[] t2 = text2.toCharArray();
        dp = new int[len1+1][len2+1];
        for(int i=0; i<=len1; i++) dp[i][0]=0;
        for(int i=0; i<=len2; i++) dp[0][i]=0;

        for(int i=1;i<len1+1;i++){
            for(int j=1;j<len2+1;j++) {
                if (t1[i-1]==t2[j-1]) dp[i][j]= 1 + dp[i-1][j-1];
                else {
                    int max = Math.max(dp[i - 1][j], dp[i][j - 1]);
                    dp[i][j] = max;
                }
            }
        }
        return dp[len1][len2];
    }

    public static void printDP(){
        for (int i = 0; i < dp.length; i++) {
            for (int j = 0; j < dp[0].length; j++) {
                System.out.print(dp[i][j] + " ");
            }
            System.out.println();
        }
    }
}
