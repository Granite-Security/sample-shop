package org.granitesecurity.greetings.research;

public class Rainbow {
    static void main() {
        String ruleset = "ABC";
        String rainbow = "A9BCi";
        boolean accepted = true;
        for(int i=0, j=0; i<rainbow.length(); i++, j++){
            while(!isAlpha(rainbow.charAt(i))) i++;
            if(rainbow.charAt(i)!=ruleset.charAt(j)){
                System.out.println("Rainbow denied! "+i);
                accepted = false;
                break;
            }
        }
        if(accepted) System.out.println("Rainbow accepted! 0");
    }

    public static boolean isAlpha(char c){
        return c >= 'A' && c <= 'Z';
    }
}
