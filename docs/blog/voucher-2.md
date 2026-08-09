Code is cheap, right?
So why not have your own Central Bank that prints money in your store instead of implementing vouchers?
We can also implement our own small accounting microservice according to IFRS?

Imagine you gift someone 50 CHF, they topup 10CHF and then buy chocolate for 60 CHF.

The whole treatment is one journal at delivery:

CHF 60 order, CHF 50 gifted credit, CHF 10 from a top-up

on payment    Dr 2000 Stored value        10                                                                                                                             
Cr 2010 Deferred revenue    10     ← net transaction price only                                                                                        
(the gifted 50 never enters the books)

on delivery   Dr 2010 Deferred revenue    10                                                                                                                             
Dr 4100 Contra-revenue      50                                                                                                                             
Cr 4000 Revenue             60     ← gross, so the discount stays visible

net revenue = 10  