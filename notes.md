- ordem das transacoes por fee, mas para o mesmo client ordenar por nonce 
- para liveness do sistema basta haverem sempre newViews apos timeout ou é necessario estarem sempre a ser propostos novos blocos mesmo que sem transacoes?



feito:

- Gestao de pruing da arvore para valores conflituosos (quando locked, dar prune aos nao locked)
criar testes em que isto acontece (valores conflituosos)
- linha no verifyQC -> DONE
- 2f+1 - fix code e report -> DONE
- guaranteee that the f+1 received responses are actually identical (usar hash) -> DONE
- precissamos de sequence numbers no nivel da aplicacao para evitar replay attacks (a aplicacao guarda o estado dos clients: "O paulo tem 10 transacoes") -> DONE
- clients precisam de assinar pedidos e replicas de verificar -> DONE

- Before the server rejected any client request whose requestId was not strictly greater than the highest seen one. That was too brittle over UDP and incompatible with the richer transaction flow we will need in Stage 2. A valid delayed request can be discarded just because a later one arrived first.
Por isso alterei modo como client requests sao tracked no server -> no message handler, já nao se ignoram requests com lower sequence numbers -> agora o message handler tem um callback para o coordinator, que é chamado quando um request é committed, e o message handler marca esse request como executed (e responde ao cliente) -> isto é necessario para evitar replay attacks, e para garantir que o client recebe resposta mesmo que o request seja re-proposto varias vezes (por exemplo, se o leader falha depois de propor mas antes de commit)
