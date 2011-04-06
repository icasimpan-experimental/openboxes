package org.pih.warehouse.inventory;

import java.util.Map;

import org.apache.commons.collections.FactoryUtils;
import org.apache.commons.collections.ListUtils;
import org.grails.plugins.excelimport.ExcelImportUtils;
import org.pih.warehouse.core.Person;
import org.pih.warehouse.core.User;
import org.pih.warehouse.product.Category;
import org.pih.warehouse.product.Product;
import org.pih.warehouse.shipping.Container;
import org.pih.warehouse.shipping.Shipment;
import org.pih.warehouse.shipping.ShipmentItem;
import org.pih.warehouse.inventory.TransactionType;
import org.pih.warehouse.inventory.StockCardCommand
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest;

import grails.converters.*

class InventoryItemController {

	def inventoryService;
	def shipmentService;

	def importInventoryItems = { ImportInventoryCommand cmd ->
		def inventoryMapList = null;
		if ("POST".equals(request.getMethod())) {			
			File localFile = null;
			if (request instanceof DefaultMultipartHttpServletRequest) { 
				def uploadFile = request.getFile('xlsFile');
				if (!uploadFile?.empty) {
					try { 
						localFile = new File("uploads/" + uploadFile.originalFilename);
						localFile.mkdirs()				
						uploadFile.transferTo(localFile);
						session.localFile = localFile;
						//flash.message = "File uploaded successfully"
						
					} catch (Exception e) { 
						throw new RuntimeException(e);
					}
				}
				else { 
					flash.message = "Please upload a non-empty file"
				}
			}
			// Otherwise, we need to retrieve the file from the session 
			else { 
				localFile = session.localFile
			}
			
			if (localFile) {
				log.info "Local xls file " + localFile.getAbsolutePath()
				cmd.filename = localFile.getAbsolutePath()
							
				Warehouse warehouse = Warehouse.get(session.warehouse.id)
				
										
				inventoryMapList =
					inventoryService.prepareInventory(warehouse, cmd.filename, cmd.errors);

				if (!inventoryMapList?.isEmpty) { 
					flash.message = "Please ensure that there is data on 'Sheet1' of '" + localFile.getAbsolutePath() + "'."
				}
				else { 
					flash.message = "Data is ready to be imported.  Please review the data below and click the 'Import Now' button below to proceed."
				}
					
				// If there are no errors and the user requests to import the data, we should execute the import
				if (!cmd.errors.hasErrors() && params.importNow) {
					inventoryService.importInventory(warehouse, inventoryMapList, cmd.errors);
					
					if (!cmd.errors.hasErrors()) {
						flash.message = "Congratulations!  You have successfully imported inventory from '" + localFile.getAbsolutePath() + "'.";
						redirect(action: "importInventoryItems");
						return;
					}
				}

				
				render(view: "importInventoryItems", model: [ inventoryMapList : inventoryMapList, commandInstance: cmd]);
			}		
			else { 
				flash.message = "Please upload a valid XLS file in order to start the import process"
			}
			
		}
	}	
	
	def show = {
		def itemInstance = InventoryItem.get(params.id)
		//def inventoryItemList = inventoryService.getInventoryItemsByProduct(productInstance)
		def transactionEntryList = TransactionEntry.findAllByInventoryItem(itemInstance)
		[
			itemInstance : itemInstance,
			transactionEntryList : transactionEntryList
		]
	}
	
	/**
	 * Ajax method for the Record Inventory page.
	 */
	def getInventoryItems = { 
		log.info params
		def productInstance = Product.get(params?.product?.id);
		def inventoryItemList = inventoryService.getInventoryItemsByProduct(productInstance)
		render inventoryItemList as JSON;
	}
	
	/**
	 * Displays the stock card for a product
	 */
	def showStockCard = { StockCardCommand cmd ->
		// add the current warehouse to the command object
		cmd.warehouseInstance = Warehouse.get(session?.warehouse?.id)
		
		// now populate the rest of the commmand object
		def commandInstance = inventoryService.getStockCardCommand(cmd, params)
		
		// populate the pending shipments
		// TODO: move this into the service layer after we find a way to add shipping service to inventory service
		// (that is, find a workaround to GRAILS-5080)
		commandInstance.pendingShipmentList = shipmentService.getPendingShipments(commandInstance.warehouseInstance);
		
		[ commandInstance: commandInstance ]
	}

	
	/**
	 * Display the Record Inventory form for the product 
	 */
	def showRecordInventory = { RecordInventoryCommand cmd -> 
		def commandInstance = inventoryService.getRecordInventoryCommand(cmd, params)
		
		
		// We need to set the inventory instance in order to save an 'inventory' transaction
		def warehouseInstance = Warehouse.get(session?.warehouse?.id)				
		def productInstance = cmd.product;
		def inventoryInstance = warehouseInstance?.inventory;
		
		def inventoryLevelInstance = InventoryLevel.findByProductAndInventory(productInstance, inventoryInstance);
		def transactionEntryList = inventoryService.getTransactionEntriesByProductAndInventory(productInstance, inventoryInstance);
		
		// Compute the total quantity for the given 
		def totalQuantity = (transactionEntryList)?transactionEntryList.quantity.sum():0
		
		[ commandInstance : commandInstance, inventoryInstance: warehouseInstance.inventory, inventoryLevelInstance: inventoryLevelInstance, totalQuantity: totalQuantity ]
	}
	
	def saveRecordInventory = { RecordInventoryCommand cmd ->
		//flash.message = "Trying to save a record inventory command object";		
		// The cmd.newRow object is not being bound correctly, so we need to use two command objects
		//cmd.recordInventoryRow = recordInventoryRow;

		//def ric = new RecordInventoryCommand()
		//cmd.recordInventoryRow = cmd2
		//bindData(ric, params)
		//bindData(cmd.recordInventoryRows, params)
		//bindData(cmd.recordInventoryRow, params)
		
		log.info ("Before saving record inventory")				
		inventoryService.saveRecordInventoryCommand(cmd, params)
		if (!cmd.hasErrors()) { 
			log.info ("No errors, show stock card")				
			redirect(action: "showStockCard", params: ['product.id':cmd.product.id])
			return;
		}
			
		log.info ("User chose to validate or there are errors")
		
		//chain(action: "showRecordInventory", model: [commandInstance:cmd])
		def warehouseInstance = Warehouse.get(session?.warehouse?.id)
		def productInstance = cmd.product;
		def transactionEntryList = TransactionEntry.findAllByProduct(productInstance);
		def totalQuantity = (transactionEntryList)?transactionEntryList.quantity.sum():0
		def inventoryInstance = warehouseInstance?.inventory;
		def inventoryLevelInstance = InventoryLevel.findByProductAndInventory(productInstance, inventoryInstance);
		
		render(view: "showRecordInventory", model: 
			[ commandInstance : cmd, inventoryInstance: warehouseInstance.inventory, inventoryLevelInstance: inventoryLevelInstance, totalQuantity: totalQuantity ])
	}

	
	

	
	def showTransactions = {
		
		def warehouseInstance = Warehouse.get(session?.warehouse?.id)
		def productInstance = Product.get(params?.product?.id)
		def inventoryInstance = warehouseInstance.inventory
		def inventoryItemList = inventoryService.getInventoryItemsByProductAndInventory(productInstance, inventoryInstance)
		def transactionEntryList = TransactionEntry.findAllByProductAndInventory(productInstance, inventoryInstance)
		def inventoryLevelInstance = InventoryLevel.findByProductAndInventory(productInstance, inventoryInstance);
		
		[ 	inventoryInstance: inventoryInstance,
			inventoryLevelInstance: inventoryLevelInstance,
			productInstance: productInstance,
			inventoryItemList: inventoryItemList,
			transactionEntryList: transactionEntryList,
			transactionEntryMap: transactionEntryList.groupBy { it.transaction } ]
	}	
	
	def toggleSupported = { 
		def inventoryLevel;
		def productInstance = Product.get(params?.product?.id);
		def inventoryInstance = Inventory.get(params?.inventory?.id);
		if (productInstance && inventoryInstance) {
			inventoryLevel = inventoryService.getInventoryLevelByProductAndInventory(productInstance, inventoryInstance)
			if (!inventoryLevel) inventoryLevel = new InventoryLevel(params);
			inventoryLevel.product = productInstance;
			inventoryLevel.inventory = inventoryInstance;
			inventoryLevel.supported = !inventoryLevel.supported;	
			if (!inventoryLevel.hasErrors() && inventoryLevel.save()) { 
				// 
			}		
			else { 
				def errorMessage = "<ul>";
				inventoryLevel.errors.allErrors.each {
					errorMessage += "<li>" + it + "</li>";
				}
				errorMessage += "</ul>";
				render errorMessage;
			}
		}
		else { 
			render "Could not find product or inventory."
		}
		
		render (inventoryLevel.supported?"Yes":"No");
	}
	
	
	def updateQuantity = { 
		log.info params;
		try { 
			def productInstance = Product.get(params?.product?.id);
			def inventoryInstance = Inventory.get(params?.inventory?.id);
			if (productInstance && inventoryInstance) { 
				def successMessage = "";
				def inventoryLevel = inventoryService.getInventoryLevelByProductAndInventory(productInstance, inventoryInstance)				
				if (!inventoryLevel) inventoryLevel = new InventoryLevel(params);
				inventoryLevel.product = productInstance;
				inventoryLevel.inventory = inventoryInstance;
				inventoryLevel.supported = Boolean.TRUE;
				if (params.minQuantity) { 
					successMessage = params.minQuantity; 
					inventoryLevel.minQuantity = Integer.valueOf(params.minQuantity)
				}
				if (params.reorderQuantity) { 
					successMessage = params.reorderQuantity;
					inventoryLevel.reorderQuantity = Integer.valueOf(params.reorderQuantity)
				}
				
				if (!inventoryLevel.hasErrors() && inventoryLevel.save()) { 
					render successMessage;
				}
				else { 
					def errorMessage = "<ul>";
					inventoryLevel.errors.allErrors.each {
						errorMessage += "<li>" + it + "</li>";
					} 
					errorMessage += "</ul>";					
					render errorMessage;
				}
			}
			else { 
				render "Error: Could not find product or inventory!"
			}
		} 
		catch (Exception e) { 
			render "Error: " + e.getMessage();
		}
	}
	
	

		
	def createInventoryItem = {
		
		flash.message = "Please note that this page is temporary.  In the future, you will be able to create new inventory items through the 'Record Stock' page."; 
		
		def productInstance = Product.get(params?.product?.id)
		def inventoryInstance = Inventory.get(params?.inventory?.id)
		def itemInstance = new InventoryItem(product: productInstance)
		def inventoryLevelInstance = inventoryService.getInventoryLevelByProductAndInventory(productInstance, inventoryInstance)
		def inventoryItems = inventoryService.getInventoryItemsByProduct(productInstance);
		[itemInstance: itemInstance, inventoryInstance: inventoryInstance, inventoryItems: inventoryItems, inventoryLevelInstance: inventoryLevelInstance, totalQuantity: totalQuantity]
	}

	def saveInventoryItem = {
		log.info "save inventory item " + params
		def productInstance = Product.get(params.product.id)
		def inventoryInstance = Inventory.get(params.inventory.id)
		def inventoryItem = new InventoryItem(params)
		def inventoryItems = inventoryService.getInventoryItemsByProduct(inventoryItem.product);
		inventoryInstance.properties = params;		

		def transactionInstance = new Transaction(params);
		def transactionEntry = new TransactionEntry(params);
		if (!transactionEntry.quantity) {
			transactionEntry.errors.rejectValue("quantity", 'transactionEntry.quantity.invalid')
		}
		
		if (transactionEntry.hasErrors()) { 
			inventoryItem.errors = transactionEntry.errors
		}
		if (transactionInstance.hasErrors()) {
			inventoryItem.errors = transactionInstance.errors
		}
		
		
				
		// TODO Move all of this logic into the service layer in order to take advantage of Hibernate/Spring transactions
		if (!inventoryItem.hasErrors() && inventoryItem.save()) { 
			//flash.message = "${message(code: 'default.created.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), inventoryItem.id])}"
			//redirect(controller: "inventoryItem", action: "showStockCard", id: inventoryItem.product.id);

			// Need to create a transaction if we want the inventory item 
			// to show up in the stock card			
			transactionInstance.transactionDate = new Date();
			transactionInstance.transactionDate.clearTime(); // we only want to store the date component
			transactionInstance.transactionType = TransactionType.get(Constants.INVENTORY_TRANSACTION_TYPE_ID);
			def warehouseInstance = Warehouse.get(session.warehouse.id);
			transactionInstance.source = warehouseInstance;
			transactionInstance.inventory = warehouseInstance.inventory;
			
			transactionEntry.inventoryItem = inventoryItem;
			transactionEntry.product = inventoryItem.product;
			transactionEntry.lotNumber = params.lotNumber;
			//transactionEntry.quantity = params.quantity;
			transactionInstance.addToTransactionEntries(transactionEntry);
			
			transactionInstance.save()
			flash.message = "Saved inventory item " + inventoryItem.id + " within a new transaction " + transactionInstance.id
	
		} else { 	
			render(view: "createInventoryItem", model: [itemInstance: inventoryItem, inventoryInstance: inventoryInstance, inventoryItems: inventoryItems])
			return;
		}
		
		 
		// If all else fails, return to the show stock card page
		redirect(action: 'showStockCard', id: productInstance?.id)
	}
	


	
	
	def edit = {
		def itemInstance = InventoryItem.get(params.id)
		def inventoryInstance = Inventory.get(params?.inventory?.id)
		if (!itemInstance) {
			flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), params.id])}"
			redirect(action: "show", id: itemInstance.id)
		}
		else {
			return [itemInstance: itemInstance]
		}
	}
	
	def editWarningLevels = {
		def itemInstance = InventoryItem.get(params.id)
		def inventoryInstance = Inventory.get(params?.inventory?.id)
		if (!itemInstance) {
			flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), params.id])}"
			redirect(action: "show", id: itemInstance.id)
		}
		else {
			return [itemInstance: itemInstance]
		}
	}

	
	/**
	 * Handles form submission from Show Stock Card > Adjust Stock dialog.	
	 */
	def adjustStock = {
		log.info "Params " + params;
		def itemInstance = InventoryItem.get(params.id)
		def productInstance = InventoryItem.get(params?.product?.id)
		def inventoryInstance = Inventory.get(params?.inventory?.id)
		if (itemInstance) {
			boolean hasErrors = inventoryService.adjustStock(itemInstance, params);
			if (!itemInstance.hasErrors() && !hasErrors) {
				flash.message = "${message(code: 'default.updated.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), itemInstance.id])}"
			}
			else {
				// There were errors, so we want to display the itemInstance.errors to the user
				flash.itemInstance = itemInstance;
			}
		}
		redirect(controller: "inventoryItem", action: "showStockCard", id: productInstance?.id, params: ['inventoryItem.id':itemInstance?.id])
	}
	
	def update = {		
		
		log.info "Params " + params;
		def itemInstance = InventoryItem.get(params.id)
		def productInstance = InventoryItem.get(params?.product?.id)
		def inventoryInstance = Inventory.get(params?.inventory?.id)
		if (itemInstance) {
			if (params.version) {
				def version = params.version.toLong()
				if (itemInstance.version > version) {
					itemInstance.errors.rejectValue("version", "default.optimistic.locking.failure", [message(code: 'inventoryItem.label', default: 'Inventory Item')] as Object[], "Another user has updated this inventory item while you were editing")
					//render(view: "show", model: [itemInstance: itemInstance])
					redirect(controller: "inventoryItem", action: "showStockCard", id: productInstance?.id)
					return
				}
			}
			itemInstance.properties = params
			
			// FIXME Temporary hack to handle a chnaged values for these two fields
			itemInstance.lotNumber = params?.lotNumber?.name
			
			if (!itemInstance.hasErrors() && itemInstance.save(flush: true)) {
				flash.message = "${message(code: 'default.updated.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), itemInstance.id])}"
			}
			else {
				//flash.message = "There were errors"
			}
		}
		else {
			flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), params.id])}"
		}
		redirect(controller: "inventoryItem", action: "showStockCard", id: productInstance?.id)
	}
	
	
	
	
	def deleteTransactionEntry = { 
		def transactionEntry = TransactionEntry.get(params.id)
		def productInstance 
		if (transactionEntry) {
			productInstance = transactionEntry.product 
			transactionEntry.delete();
		}
		redirect(action: 'showStockCard', params: ['product.id':productInstance?.id])
	}
	
	def addToInventory = {
		def product = Product.get( params.id )
		render "${product.name} was added to inventory"
		//return product as XML		
	}	
	/**
	 * shipment.name:1, 
	 * shipment:[name:1, id:1], 
	 * inventory.id:1, 
	 * inventory:[id:1], 
	 * recipient.name:3, 
	 * recipient:[name:3, id:3], 
	 * product.id:8, product:[id:8], 
	 * quantity:1, 
	 * recipient.id:3, addItem:, 
	 * shipment.id:1, 
	 * inventoryItem.id:1, 
	 * inventoryItem:[id:1], 
	 * action:addToShipment, 
	 * controller:inventoryItem]
	*/
	def addToShipment = {
		log.info "params" + params
		def shipmentInstance = null;
		def containerInstance = null;
		def productInstance = Product.get(params?.product?.id);
		def personInstance = Person.get(params?.recipient?.id);
		def inventoryItem = InventoryItem.get(params?.inventoryItem?.id);
		if (params.shipment.type == 'container') { 
			containerInstance = Container.get(params?.shipment?.id);
			shipmentInstance = containerInstance.shipment;
		}
		else { 
			shipmentInstance = Shipment.get(params?.shipment?.id);
			//containerInstance = shipmentInstance?.containers?.get(0);
		}
				
		def itemInstance = new ShipmentItem(
			product: productInstance,
			quantity: params.quantity,
			recipient: personInstance,
			lotNumber: inventoryItem.lotNumber?:'',
			shipment: shipmentInstance,
			container: null);
				
		if(itemInstance.hasErrors() || !itemInstance.validate()) {
			flash.message = "There was an error validating item to be added\n" ;
			itemInstance.errors.each { flash.message += it }
			
		}

		if (!itemInstance.hasErrors()) { 
			if (!shipmentInstance.addToShipmentItems(itemInstance).save(flush:true)) {
				log.error("Sorry, unable to add new item to shipment.  Please try again.");
				flash.message = "Unable to add new item to shipment";
			}
			else { 
				def productDescription = productInstance?.name + (inventoryItem?.lotNumber) ? " #" + inventoryItem?.lotNumber : "";		
				flash.message = "Added item " + productDescription + " to shipment " + shipmentInstance?.name;
			}
		}
		
		redirect(action: "showStockCard", params: ['product.id':productInstance?.id]);
	}
	

	
		
	def saveInventoryLevel = {
		// Get existing inventory level
		def inventoryLevelInstance = InventoryLevel.get(params.id)		
		def productInstance = Product.get(params?.product?.id)
		//def inventoryInstance = Inventory.get(params?.inventory?.id);

		if (inventoryLevelInstance) { 
			inventoryLevelInstance.properties = params;
		}
		else { 
			inventoryLevelInstance = new InventoryLevel(params);
		}
		
		if (!inventoryLevelInstance.hasErrors() && inventoryLevelInstance.save()) { 
			
		}
		else { 
			flash.message = "error saving inventory levels<br/>" 
			inventoryLevelInstance.errors.allErrors.each { 
				flash.message += it + "<br/>";
			}
		}
		redirect(action: 'showStockCard', params: ['product.id':productInstance?.id])
	}
	
	/*
	def saveInventoryItem = {
		def inventory = Inventory.get(params.id)
		def inventoryItem = new InventoryItem(params)
		inventory.addToInventoryItem(inventoryItem)
		if(! inventory.hasErrors() && inventory.save()) {
			render template:'inventoryItemRow', bean:inventoryItem, var:'inventoryItem'
		}
	}
	*/
	
	

	def deleteInventoryItem = {
		def inventoryItem = InventoryItem.get(params.id);
		def productInstance = inventoryItem?.product;
		def inventoryInstance = Inventory.get(inventoryItem?.inventory?.id);
		
		if (inventoryItem && inventoryInstance) {
			inventoryInstance.removeFromInventoryItems(inventoryItem).save();
			inventoryItem.delete();
		}		
		else {
			inventoryItem.errors.reject("inventoryItem.error", "Could not delete inventory item")
			params.put("product.id", productInstance?.id);
			params.put("inventory.id", inventoryInstance?.id);
			log.info "Params " + params;
			chain(action: "createInventoryItem", model: [inventoryItem: inventoryItem], params: params)
			return;
		}
		redirect(action: 'showStockCard', params: ['product.id':productInstance?.id])
		
	}
	
	
	def saveTransactionEntry = {			
		def productInstance = Product.get(params?.product?.id)				
		if (!productInstance) {
			flash.message = "${message(code: 'default.notfound.message', args: [message(code: 'product.label', default: 'Product'), productInstance.id])}"
			redirect(action: "showStockCard", id: productInstance?.id)
		}
		else { 
			def inventoryItem = InventoryItem.findByProductAndLotNumber(productInstance, params.lotNumber?:null)
			if (!inventoryItem) { 
				flash.message = "${message(code: 'default.notfound.message', args: [message(code: 'inventoryItem.label', default: 'Inventory item'), params.lotNumber])}"
			} 
			else {  
				def transactionInstance = new Transaction(params)
				def transactionEntry = new TransactionEntry(params)
				
				// If we're transferring stock to another location OR consuming stock, 
				// then we need to make sure the quantity is negative
				if (transactionInstance?.destination?.id != session?.warehouse?.id 
					|| transactionInstance?.transactionType?.name == 'Consumption') { 
					if (transactionEntry.quantity > 0) { 
						transactionEntry.quantity = -transactionEntry.quantity;
					}
				}
				
				transactionEntry.inventoryItem = inventoryItem;
				if (!transactionEntry.hasErrors() &&
					transactionInstance.addToTransactionEntries(transactionEntry).save(flush:true)) {
					flash.message = "Saved transaction entry"
				} 
				else {
					transactionInstance.errors.each { println it }
					transactionEntry.errors.each { println it }
					flash.message = "Unable to save transaction entry"
				}
				
				/*
				if (!transactionInstance.hasErrors() && transactionInstance.save(flush:true) {
					def transactionEntry = new TransactionEntry(params)
					transactionEntry.inventoryItem = inventoryItem;					
					if (!transactionEntry.hasErrors() && 
						transactionInstance.addToTransactionEntries(transactionEntry).save(flush:true)) {
						transactionEntry.errors.each { println it }
						flash.message = "Unable to save transaction entry"
					} else { 
						flash.message = "${message(code: 'default.saved.message', args: [message(code: 'inventory.label', default: 'Inventory item'), itemInstance.id])}"					
					}
				}
				else { 
					transactionInstance.errors.each { println it }
					flash.message = "Unable to save transaction"
				}	
				*/
			}
		}
		redirect(action: "showStockCard",  params: ['product.id':productInstance?.id])		
	}
	
	
	def editItemDialog = {
		def itemInstance = InventoryItem.get(params.id);
		render(view:'editItemDialog', model: [itemInstance: itemInstance]);
	}

}


class ImportInventoryCommand { 
	
	def filename
	def importFile
	def transactionInstance
	def warehouseInstance
	def inventoryInstance
	def products
	def transactionEntries
	def categories
	def inventoryItems
	
	static constraints = {
		
	}
}

