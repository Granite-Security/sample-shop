package org.granitesecurity.greetings.research;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public class Sim {
    static int time = 0;
    
    static void main() {
        Sim sim = new Sim();
        

        String readln = IO.readln("Time / treatment: \n");
        List<Patient> patients = new ArrayList<>();
        Doctor doctor = sim.new Doctor();
        SimQueue<Patient> queue = sim.new SimQueue<>();
        
        while (readln != null && !readln.isEmpty()) {
            Patient patient = sim.new Patient(readln);
            queue.add(patient);
            
            readln = IO.readln("Time / treatment: \n");
        }
        
        while (queue.size() > 0) {
            Patient currentPatient = queue.peek();
            if(doctor.isAvailable(sim.time) && currentPatient.start <= sim.time) {
                doctor.treatPatient(currentPatient);
                queue.poll();
            }
            Sim.time++;
        }

    }
    
    private class Doctor {

        int nextAvailableTime;
        Doctor() {
            this.nextAvailableTime = 0;

        }
        public boolean isAvailable(int time) {
            return time >= nextAvailableTime;
        }
        
        void treatPatient(Patient patient) {
            System.out.println("Sim Time: "+Sim.time+"Treating patient who arrived at " + patient.start + " for " + patient.time + " minutes.");
            this.nextAvailableTime = Sim.time + patient.time;
        }
    }

    @Data
    private class SimQueue<T> {
        int start, end;
        List<T> list;
        int maxLength;
       

        SimQueue() {
            this.start = 0;
            this.end = 0;
            this.list = new ArrayList<>();
            this.maxLength = 0;
      
        }
        public int size() {
            return end - start;
        }

        public void add(T t) {
            list.add(t);
            end++;
            if (maxLength < end - start) maxLength = end - start;
        }

        public T peek() {
            if (start == end) return null;
            return list.get(start);
        }

        public T poll() {
            if (start == end) return null;
            T t = list.get(start);
            start++;
           
            return t;
        }
    }

    @Data
    private class Patient {
        int start;
        int time;

        Patient(String input) {
            String[] s = input.split(" ");
            this.start = Integer.parseInt(s[0]);
            this.time = Integer.parseInt(s[1]);
        }
    }

}
